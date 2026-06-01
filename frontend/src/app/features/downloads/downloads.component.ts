import {Component, effect, inject, OnDestroy, OnInit} from '@angular/core';
import {DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {InputNumber} from 'primeng/inputnumber';
import {Select} from 'primeng/select';
import {MultiSelect} from 'primeng/multiselect';
import {TableModule} from 'primeng/table';
import {Tag} from 'primeng/tag';
import {ProgressBar} from 'primeng/progressbar';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {Tooltip} from 'primeng/tooltip';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {finalize, switchMap} from 'rxjs/operators';
import {interval, Subscription} from 'rxjs';
import {PageTitleService} from '../../shared/service/page-title.service';
import {LibraryService} from '../book/service/library.service';
import {Library} from '../book/model/library.model';
import {DownloadsService} from './downloads.service';
import {
  DOWNLOAD_CONTENT_KINDS,
  DOWNLOAD_FORMATS,
  DownloadCanonicalCandidate,
  DownloadContentKind,
  DownloadFormat,
  DownloadJob,
  DownloadJobStatus,
  DownloadResult,
  DownloadSearchRequest,
  DownloadSequenceNumberType
} from './downloads.model';

interface SelectOption<T> {
  label: string;
  value: T;
}

@Component({
  selector: 'app-downloads',
  imports: [
    Button,
    InputText,
    InputNumber,
    Select,
    MultiSelect,
    TableModule,
    Tag,
    ProgressBar,
    ToggleSwitch,
    Tooltip,
    FormsModule,
    DatePipe,
    TranslocoDirective
  ],
  templateUrl: './downloads.component.html',
  styleUrl: './downloads.component.scss'
})
export class DownloadsComponent implements OnInit, OnDestroy {
  private readonly downloadsService = inject(DownloadsService);
  private readonly libraryService = inject(LibraryService);
  private readonly messageService = inject(MessageService);
  private readonly pageTitle = inject(PageTitleService);
  private readonly t = inject(TranslocoService);

  query = '';
  title = '';
  author = '';
  isbn = '';
  seriesName = '';
  seriesNumber: number | null = null;
  sequenceNumberType: DownloadSequenceNumberType = 'AUTO';
  directUrl = '';
  contentKind: DownloadContentKind = 'AUTO';
  preferredFormats: DownloadFormat[] = [...DOWNLOAD_FORMATS];
  maxResults = 25;
  autoFinalize = true;
  confidenceThreshold = 90;
  targetLibraryId: number | null = null;
  targetLibraryPathId: number | null = null;

  results: DownloadResult[] = [];
  canonicalCandidates: DownloadCanonicalCandidate[] = [];
  jobs: DownloadJob[] = [];
  libraries: Library[] = [];
  searchId: number | null = null;
  searchError: string | null = null;
  loadingResults = false;
  resolvingCanonical = false;
  searchElapsedSeconds = 0;
  lastSearchDurationMs: number | null = null;
  searchProgressKey = 'downloads.search.progressStarting';
  loadingJobs = false;
  cleanupRunning = false;
  acquiringResultIds = new Set<number>();
  processingJobIds = new Set<number>();
  retryingJobIds = new Set<number>();

  contentKindOptions: SelectOption<DownloadContentKind>[] = DOWNLOAD_CONTENT_KINDS.map(value => ({label: this.contentKindLabel(value), value}));
  formatOptions: SelectOption<DownloadFormat>[] = DOWNLOAD_FORMATS.map(value => ({label: value, value}));

  private pollSub?: Subscription;
  private searchProgressTimer?: ReturnType<typeof setInterval>;
  private searchStartedAt = 0;
  private canonicalSearchSignature: string | null = null;

  constructor() {
    effect(() => {
      this.libraries = this.libraryService.libraries();
      this.ensureValidTargetSelection();
      if (this.autoFinalize) {
        this.applyDefaultTargetIfSingle();
      }
    });
  }

  ngOnInit(): void {
    this.pageTitle.setPageTitle('Downloads');
    this.loadJobs();
    this.pollSub = interval(5000).subscribe(() => this.loadJobs(false));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.clearSearchProgressTimer();
  }

  search(): void {
    const request = this.buildSearchRequest();
    if (!request.query && !request.title && !request.isbn && !request.directUrl) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('downloads.toast.searchRequiredSummary'),
        detail: this.t.translate('downloads.toast.searchRequiredDetail')
      });
      return;
    }

    if (this.shouldResolveBeforeSearch(request)) {
      this.resolveBeforeSearch(request);
      return;
    }

    this.runSourceSearch(request);
  }

  private runSourceSearch(request: DownloadSearchRequest): void {
    this.startSearchProgress();
    this.searchError = null;
    this.downloadsService.search(request).pipe(
      finalize(() => this.stopSearchProgress())
    ).subscribe({
      next: response => {
        this.searchId = response.id;
        this.searchError = response.errorMessage ?? null;
        this.results = [...(response.results ?? [])]
          .filter(result => (result.score ?? 0) >= 50)
          .sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
        if (!this.results.length) {
          this.messageService.add({
            severity: this.searchError ? 'warn' : 'info',
            summary: this.t.translate('downloads.toast.noResultsSummary'),
            detail: this.searchError || this.t.translate('downloads.toast.noResultsDetail')
          });
        }
      },
      error: err => {
        this.searchError = err?.error?.message || err?.message || this.t.translate('downloads.toast.searchError');
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.searchError ?? this.t.translate('downloads.toast.searchError')
        });
      }
    });
  }

  acquire(result: DownloadResult): void {
    if (this.acquiringResultIds.has(result.id)) return;

    this.acquiringResultIds.add(result.id);
    this.downloadsService.queueSelectedResult(result.id, {
      autoFinalize: this.autoFinalize,
      confidenceThreshold: this.confidenceThreshold,
      targetLibraryId: this.targetLibraryId,
      targetLibraryPathId: this.targetLibraryPathId
    }).pipe(
      switchMap(job => {
        this.processingJobIds.add(job.id);
        this.loadJobs(false);
        this.messageService.add({
          severity: 'info',
          summary: this.t.translate('downloads.toast.jobQueuedSummary'),
          detail: this.t.translate('downloads.toast.jobQueuedDetail', {id: job.id})
        });
        return this.downloadsService.processJob(job.id).pipe(
          finalize(() => this.processingJobIds.delete(job.id))
        );
      }),
      finalize(() => this.acquiringResultIds.delete(result.id))
    ).subscribe({
      next: job => {
        this.loadJobs(false);
        this.showJobToast(job);
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.acquireError')
        });
      }
    });
  }

  processJob(job: DownloadJob): void {
    if (this.processingJobIds.has(job.id)) return;
    this.processingJobIds.add(job.id);
    this.downloadsService.processJob(job.id).pipe(
      finalize(() => this.processingJobIds.delete(job.id))
    ).subscribe({
      next: processed => {
        this.loadJobs(false);
        this.showJobToast(processed);
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.processError')
        });
      }
    });
  }

  private resolveBeforeSearch(request: DownloadSearchRequest): void {
    this.resolvingCanonical = true;
    this.searchError = null;
    this.downloadsService.resolve(request).pipe(
      finalize(() => this.resolvingCanonical = false)
    ).subscribe({
      next: candidates => {
        this.canonicalCandidates = candidates ?? [];
        if (this.canonicalCandidates.length) {
          this.results = [];
          this.searchId = null;
          this.messageService.add({
            severity: 'info',
            summary: this.t.translate('downloads.resolve.title'),
            detail: this.t.translate('downloads.resolve.description')
          });
          return;
        }
        this.runSourceSearch(request);
      },
      error: err => {
        this.searchError = err?.error?.message || err?.message || this.t.translate('downloads.resolve.error');
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.searchError ?? this.t.translate('downloads.resolve.error')
        });
      }
    });
  }

  resolveCanonical(): void {
    const request = this.buildSearchRequest();
    if (!request.query && !request.title && !request.isbn && !request.seriesName) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('downloads.toast.searchRequiredSummary'),
        detail: this.t.translate('downloads.toast.resolveRequiredDetail')
      });
      return;
    }
    if (request.directUrl) {
      this.messageService.add({
        severity: 'info',
        summary: this.t.translate('downloads.resolve.skippedDirectUrlSummary'),
        detail: this.t.translate('downloads.resolve.skippedDirectUrlDetail')
      });
      return;
    }

    this.resolvingCanonical = true;
    this.downloadsService.resolve(request).pipe(
      finalize(() => this.resolvingCanonical = false)
    ).subscribe({
      next: candidates => {
        this.canonicalCandidates = candidates ?? [];
        if (!this.canonicalCandidates.length) {
          this.messageService.add({
            severity: 'info',
            summary: this.t.translate('downloads.resolve.noneSummary'),
            detail: this.t.translate('downloads.resolve.noneDetail')
          });
        }
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.resolve.error')
        });
      }
    });
  }

  applyCanonical(candidate: DownloadCanonicalCandidate): void {
    this.query = candidate.query || this.query;
    this.title = candidate.resolvedTitle || candidate.title || this.title;
    this.author = candidate.resolvedAuthor || candidate.author || this.author;
    this.isbn = candidate.resolvedIsbn || candidate.isbn || this.isbn;
    this.seriesName = candidate.resolvedSeriesName || candidate.seriesName || this.seriesName;
    this.seriesNumber = candidate.seriesNumber ?? this.seriesNumber;
    this.sequenceNumberType = candidate.sequenceNumberType || this.sequenceNumberType || 'AUTO';
    if (candidate.contentKind && candidate.contentKind !== 'AUTO') {
      this.contentKind = candidate.contentKind;
    }
    this.canonicalCandidates = [];
    this.canonicalSearchSignature = this.requestSignature(this.buildSearchRequest());
    this.messageService.add({
      severity: 'success',
      summary: this.t.translate('downloads.resolve.appliedSummary'),
      detail: this.t.translate('downloads.resolve.appliedDetail', {title: this.canonicalCandidateTitle(candidate)})
    });
    this.search();
  }

  retryJob(job: DownloadJob): void {
    if (!this.canRetry(job) || this.retryingJobIds.has(job.id)) return;
    this.retryingJobIds.add(job.id);
    this.downloadsService.retryJob(job.id).pipe(
      finalize(() => this.retryingJobIds.delete(job.id))
    ).subscribe({
      next: retried => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'info',
          summary: this.t.translate('downloads.toast.jobRetrySummary'),
          detail: this.t.translate('downloads.toast.jobRetryDetail', {oldId: job.id, id: retried.id})
        });
        this.showJobToast(retried);
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.retryError')
        });
      }
    });
  }

  loadJobs(showLoader = true): void {
    if (showLoader) {
      this.loadingJobs = true;
    }
    this.downloadsService.listJobs().subscribe({
      next: jobs => {
        this.jobs = jobs ?? [];
        this.loadingJobs = false;
      },
      error: () => {
        this.loadingJobs = false;
      }
    });
  }

  cleanup(): void {
    this.cleanupRunning = true;
    this.downloadsService.cleanupNow().pipe(
      finalize(() => this.cleanupRunning = false)
    ).subscribe({
      next: () => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('downloads.toast.cleanupDone')
        });
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || this.t.translate('downloads.toast.cleanupError')
        });
      }
    });
  }

  resetSearch(): void {
    this.query = '';
    this.title = '';
    this.author = '';
    this.isbn = '';
    this.seriesName = '';
    this.seriesNumber = null;
    this.sequenceNumberType = 'AUTO';
    this.directUrl = '';
    this.contentKind = 'AUTO';
    this.preferredFormats = [...DOWNLOAD_FORMATS];
    this.maxResults = 25;
    this.results = [];
    this.canonicalCandidates = [];
    this.canonicalSearchSignature = null;
    this.searchId = null;
    this.searchError = null;
  }

  authors(result: DownloadResult): string {
    if (!result.authorsJson) return '';
    try {
      const value = JSON.parse(result.authorsJson);
      return Array.isArray(value) ? value.filter(Boolean).join(', ') : '';
    } catch {
      return '';
    }
  }

  resultMeta(result: DownloadResult): string {
    const parts = [
      this.authors(result),
      this.isVisualResult(result) && result.seriesName && result.title !== result.seriesName
        ? `${result.title}${result.seriesNumber ? ` #${result.seriesNumber}` : ''}`
        : result.seriesName ? `${result.seriesName}${result.seriesNumber ? ` #${result.seriesNumber}` : ''}` : null,
      result.publishedYear,
      result.language
    ].filter(Boolean);
    return parts.join(' · ');
  }

  displayTitle(result: DownloadResult): string {
    return this.isVisualResult(result) && result.seriesName ? result.seriesName : result.title;
  }

  scoreSeverity(score?: number | null): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    if (score == null) return 'secondary';
    if (score >= 90) return 'success';
    if (score >= 70) return 'info';
    if (score >= 50) return 'warn';
    return 'danger';
  }

  jobSeverity(status: DownloadJobStatus): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'COMPLETED':
        return 'success';
      case 'PENDING_REVIEW':
      case 'QUEUED':
        return 'info';
      case 'DOWNLOADING':
      case 'VALIDATING':
      case 'DELIVERING':
      case 'AUTO_FINALIZING':
      case 'SEARCHING':
      case 'SCORING':
      case 'STAGED':
        return 'warn';
      case 'FAILED':
      case 'CANCELLED':
        return 'danger';
      default:
        return 'secondary';
    }
  }

  jobSeverityFor(job: DownloadJob): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    return this.isStaleJob(job) ? 'danger' : this.jobSeverity(job.status);
  }

  statusLabel(status: DownloadJobStatus): string {
    return this.t.translate(`downloads.statuses.${status}`);
  }

  contentKindLabel(kind: DownloadContentKind): string {
    return this.t.translate(`downloads.contentKinds.${kind}`);
  }

  canonicalCandidateTitle(candidate: DownloadCanonicalCandidate): string {
    return candidate.resolvedSeriesName || candidate.seriesName || candidate.resolvedTitle || candidate.title || candidate.query || '-';
  }

  canonicalCandidateMeta(candidate: DownloadCanonicalCandidate): string {
    const number = candidate.seriesNumber == null
      ? null
      : `${candidate.sequenceNumberType && candidate.sequenceNumberType !== 'AUTO' ? candidate.sequenceNumberType.toLowerCase() : '#'} ${candidate.seriesNumber}`;
    return [
      candidate.provider,
      this.contentKindLabel(candidate.contentKind),
      candidate.resolvedAuthor || candidate.author,
      candidate.resolvedIsbn || candidate.isbn ? `ISBN ${candidate.resolvedIsbn || candidate.isbn}` : null,
      number
    ].filter(Boolean).join(' · ');
  }

  canonicalConfidence(candidate: DownloadCanonicalCandidate): string {
    return `${Math.round((candidate.confidence ?? 0) * 100)}%`;
  }

  acquisitionLabel(acquisitionType: string): string {
    return this.t.translate(`downloads.acquisitionTypes.${acquisitionType}`);
  }

  contentKindSeverity(kind: DownloadContentKind): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (kind) {
      case 'BOOK':
        return 'info';
      case 'MANGA':
        return 'success';
      case 'COMIC':
        return 'warn';
      case 'WEBTOON':
        return 'danger';
      default:
        return 'secondary';
    }
  }

  private isVisualResult(result: DownloadResult): boolean {
    return ['MANGA', 'COMIC', 'WEBTOON'].includes(result.contentKind);
  }

  get targetLibraryOptions(): SelectOption<number>[] {
    return this.libraries
      .filter(lib => lib.id != null)
      .map(lib => ({label: lib.name, value: Number(lib.id)}));
  }

  get targetPathOptions(): SelectOption<number>[] {
    const selected = this.libraries.find(lib => lib.id === this.targetLibraryId);
    return selected?.paths
      ?.filter(path => path.id != null)
      .map(path => ({label: path.path, value: Number(path.id)})) ?? [];
  }

  onAutoFinalizeChange(enabled: boolean): void {
    if (enabled) {
      this.applyDefaultTargetIfSingle();
    }
  }

  onTargetLibraryChange(): void {
    const paths = this.targetPathOptions;
    this.targetLibraryPathId = paths.length === 1 ? paths[0].value : null;
  }

  scoreLabel(score?: number | null): string {
    return score == null ? '-' : `${score}/100`;
  }

  searchProgressMessage(): string {
    return this.t.translate(this.searchProgressKey, {seconds: this.searchElapsedSeconds});
  }

  lastSearchDurationLabel(): string {
    if (!this.lastSearchDurationMs) return '';
    const seconds = this.lastSearchDurationMs / 1000;
    return this.t.translate('downloads.results.completedIn', {seconds: seconds.toFixed(seconds >= 10 ? 0 : 1)});
  }

  formatBytes(bytes?: number | null): string {
    if (!bytes || bytes <= 0) return '-';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
  }

  canProcess(job: DownloadJob): boolean {
    return job.status === 'QUEUED' && !this.processingJobIds.has(job.id);
  }

  canRetry(job: DownloadJob): boolean {
    return !this.retryingJobIds.has(job.id) && (job.status === 'FAILED' || job.status === 'CANCELLED' || this.isStaleJob(job));
  }

  isActiveJob(job: DownloadJob): boolean {
    return ['QUEUED', 'SEARCHING', 'SCORING', 'DOWNLOADING', 'VALIDATING', 'STAGED', 'DELIVERING', 'AUTO_FINALIZING'].includes(job.status);
  }

  isStaleJob(job: DownloadJob): boolean {
    if (!this.isActiveJob(job)) {
      return false;
    }
    const timestamp = Date.parse(job.updatedAt || job.createdAt || '');
    if (Number.isNaN(timestamp)) {
      return false;
    }
    return Date.now() - timestamp > 10 * 60 * 1000;
  }

  trackById(_: number, item: {id: number}): number {
    return item.id;
  }

  private buildSearchRequest(): DownloadSearchRequest {
    return {
      query: this.clean(this.query),
      title: this.clean(this.title),
      author: this.clean(this.author),
      isbn: this.clean(this.isbn),
      seriesName: this.clean(this.seriesName),
      seriesNumber: this.seriesNumber,
      sequenceNumberType: this.sequenceNumberType,
      directUrl: this.clean(this.directUrl),
      contentKind: this.contentKind,
      preferredFormats: this.preferredFormats,
      maxResults: Math.max(1, Math.min(100, this.maxResults || 25))
    };
  }

  private clean(value: string): string | null {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }

  private shouldResolveBeforeSearch(request: DownloadSearchRequest): boolean {
    if (request.directUrl) {
      return false;
    }
    if (!request.query && !request.title && !request.isbn && !request.seriesName) {
      return false;
    }
    return this.canonicalSearchSignature !== this.requestSignature(request);
  }

  private requestSignature(request: DownloadSearchRequest): string {
    return JSON.stringify({
      query: request.query ?? null,
      title: request.title ?? null,
      author: request.author ?? null,
      isbn: request.isbn ?? null,
      seriesName: request.seriesName ?? null,
      seriesNumber: request.seriesNumber ?? null,
      sequenceNumberType: request.sequenceNumberType ?? 'AUTO',
      contentKind: request.contentKind ?? 'AUTO'
    });
  }

  private startSearchProgress(): void {
    this.clearSearchProgressTimer();
    this.loadingResults = true;
    this.searchElapsedSeconds = 0;
    this.lastSearchDurationMs = null;
    this.searchStartedAt = Date.now();
    this.searchProgressKey = 'downloads.search.progressStarting';
    this.searchProgressTimer = setInterval(() => {
      this.searchElapsedSeconds = Math.floor((Date.now() - this.searchStartedAt) / 1000);
      if (this.searchElapsedSeconds >= 8) {
        this.searchProgressKey = 'downloads.search.progressSlow';
      } else if (this.searchElapsedSeconds >= 3) {
        this.searchProgressKey = 'downloads.search.progressFlareSolverr';
      } else {
        this.searchProgressKey = 'downloads.search.progressStarting';
      }
    }, 500);
  }

  private stopSearchProgress(): void {
    if (this.searchStartedAt) {
      this.lastSearchDurationMs = Date.now() - this.searchStartedAt;
    }
    this.loadingResults = false;
    this.clearSearchProgressTimer();
  }

  private clearSearchProgressTimer(): void {
    if (this.searchProgressTimer) {
      clearInterval(this.searchProgressTimer);
      this.searchProgressTimer = undefined;
    }
  }

  private applyDefaultTargetIfSingle(): void {
    if (!this.targetLibraryId && this.targetLibraryOptions.length === 1) {
      this.targetLibraryId = this.targetLibraryOptions[0].value;
    }

    if (this.targetLibraryId && !this.targetLibraryPathId) {
      const paths = this.targetPathOptions;
      if (paths.length === 1) {
        this.targetLibraryPathId = paths[0].value;
      }
    }
  }

  private ensureValidTargetSelection(): void {
    if (this.targetLibraryId && !this.libraries.some(lib => lib.id === this.targetLibraryId)) {
      this.targetLibraryId = null;
      this.targetLibraryPathId = null;
      return;
    }

    if (this.targetLibraryPathId && !this.targetPathOptions.some(path => path.value === this.targetLibraryPathId)) {
      this.targetLibraryPathId = null;
    }
  }

  private showJobToast(job: DownloadJob): void {
    if (job.status === 'FAILED') {
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('downloads.toast.jobFailedSummary'),
        detail: job.errorMessage || this.t.translate('downloads.toast.jobFinishedDetail', {id: job.id, status: this.statusLabel(job.status)})
      });
      return;
    }

    if (this.isTerminalStatus(job.status)) {
      this.messageService.add({
        severity: 'success',
        summary: this.t.translate('downloads.toast.jobFinishedSummary'),
        detail: this.t.translate('downloads.toast.jobFinishedDetail', {id: job.id, status: this.statusLabel(job.status)})
      });
      return;
    }

    this.messageService.add({
      severity: 'info',
      summary: this.t.translate('downloads.toast.jobStartedSummary'),
      detail: this.t.translate('downloads.toast.jobStartedDetail', {id: job.id, status: this.statusLabel(job.status)})
    });
  }

  private isTerminalStatus(status: DownloadJobStatus): boolean {
    return ['COMPLETED', 'PENDING_REVIEW', 'FAILED', 'CANCELLED'].includes(status);
  }
}
