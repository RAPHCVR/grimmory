import {Component, inject, OnDestroy, OnInit} from '@angular/core';
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
  DownloadContentKind,
  DownloadFormat,
  DownloadJob,
  DownloadJobStatus,
  DownloadResult,
  DownloadSearchRequest
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
  directUrl = '';
  contentKind: DownloadContentKind = 'BOOK';
  preferredFormats: DownloadFormat[] = ['EPUB', 'PDF', 'CBZ'];
  maxResults = 25;
  autoFinalize = false;
  confidenceThreshold = 90;
  targetLibraryId: number | null = null;
  targetLibraryPathId: number | null = null;

  results: DownloadResult[] = [];
  jobs: DownloadJob[] = [];
  libraries: Library[] = [];
  searchId: number | null = null;
  searchError: string | null = null;
  loadingResults = false;
  loadingJobs = false;
  cleanupRunning = false;
  acquiringResultIds = new Set<number>();
  processingJobIds = new Set<number>();

  contentKindOptions: SelectOption<DownloadContentKind>[] = DOWNLOAD_CONTENT_KINDS.map(value => ({label: value, value}));
  formatOptions: SelectOption<DownloadFormat>[] = DOWNLOAD_FORMATS.map(value => ({label: value, value}));

  private pollSub?: Subscription;
  private librarySub?: Subscription;

  ngOnInit(): void {
    this.pageTitle.setPageTitle('Downloads');
    this.librarySub = this.libraryService.libraryState$.subscribe(state => {
      this.libraries = state.libraries ?? [];
      this.ensureValidTargetSelection();
      if (this.autoFinalize) {
        this.applyDefaultTargetIfSingle();
      }
    });
    this.loadJobs();
    this.pollSub = interval(5000).subscribe(() => this.loadJobs(false));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.librarySub?.unsubscribe();
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

    this.loadingResults = true;
    this.searchError = null;
    this.downloadsService.search(request).subscribe({
      next: response => {
        this.loadingResults = false;
        this.searchId = response.id;
        this.searchError = response.errorMessage ?? null;
        this.results = [...(response.results ?? [])].sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
        if (!this.results.length) {
          this.messageService.add({
            severity: 'info',
            summary: this.t.translate('downloads.toast.noResultsSummary'),
            detail: this.t.translate('downloads.toast.noResultsDetail')
          });
        }
      },
      error: err => {
        this.loadingResults = false;
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
    if (this.autoFinalize && !this.hasAutoFinalizeTarget()) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('downloads.toast.targetRequiredSummary'),
        detail: this.t.translate('downloads.toast.targetRequiredDetail')
      });
      return;
    }

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
    this.directUrl = '';
    this.contentKind = 'BOOK';
    this.preferredFormats = ['EPUB', 'PDF', 'CBZ'];
    this.maxResults = 25;
    this.results = [];
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
      result.seriesName ? `${result.seriesName}${result.seriesNumber ? ` #${result.seriesNumber}` : ''}` : null,
      result.publishedYear,
      result.language
    ].filter(Boolean);
    return parts.join(' · ');
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

  statusLabel(status: DownloadJobStatus): string {
    return this.t.translate(`downloads.statuses.${status}`);
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

  get autoFinalizeTargetMissing(): boolean {
    return this.autoFinalize && !this.hasAutoFinalizeTarget();
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

  isActiveJob(job: DownloadJob): boolean {
    return ['QUEUED', 'SEARCHING', 'SCORING', 'DOWNLOADING', 'VALIDATING', 'STAGED', 'DELIVERING', 'AUTO_FINALIZING'].includes(job.status);
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

  private hasAutoFinalizeTarget(): boolean {
    return this.targetLibraryId != null && this.targetLibraryPathId != null;
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
