import {ChangeDetectorRef, Component, effect, inject, OnDestroy, OnInit} from '@angular/core';
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
import {Dialog} from 'primeng/dialog';
import {MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {finalize, switchMap} from 'rxjs/operators';
import {interval, of, Subscription} from 'rxjs';
import {PageTitleService} from '../../shared/service/page-title.service';
import {LibraryService} from '../book/service/library.service';
import {Library} from '../book/model/library.model';
import {DownloadsService} from './downloads.service';
import {
  DOWNLOAD_CONTENT_KINDS,
  DOWNLOAD_FORMATS,
  DownloadCanonicalCandidate,
  DownloadCanonicalSelection,
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

interface CanonicalAppliedState {
  query: string | null;
  title: string;
  author: string;
  isbn: string;
  seriesName: string;
  seriesNumber: number | null;
  seriesNumberEnd: number | null;
  preferredLanguage: string | null;
  sequenceNumberType: DownloadSequenceNumberType;
  contentKind: DownloadContentKind;
}

interface ResultQualityBadge {
  labelKey: string;
  tooltipKey: string;
  severity: 'success' | 'info' | 'warn' | 'danger' | 'secondary';
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
    Dialog,
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
  private readonly cdr = inject(ChangeDetectorRef);

  query = '';
  title = '';
  author = '';
  isbn = '';
  seriesName = '';
  seriesNumber: number | null = null;
  seriesNumberEnd: number | null = null;
  preferredLanguage: string | null = 'fr';
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
  allResults: DownloadResult[] = [];
  hiddenWeakResultCount = 0;
  readonly weakResultThreshold = 50;
  showWeakResults = false;
  canonicalCandidates: DownloadCanonicalCandidate[] = [];
  selectedCanonicalCandidate: DownloadCanonicalCandidate | null = null;
  canonicalDetailsCandidate: DownloadCanonicalCandidate | null = null;
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
  archivingJobIds = new Set<number>();

  contentKindOptions: SelectOption<DownloadContentKind>[] = DOWNLOAD_CONTENT_KINDS.map(value => ({label: this.contentKindLabel(value), value}));
  formatOptions: SelectOption<DownloadFormat>[] = DOWNLOAD_FORMATS.map(value => ({label: value, value}));
  sequenceNumberTypeOptions: SelectOption<DownloadSequenceNumberType>[] = ['AUTO', 'VOLUME', 'ISSUE', 'CHAPTER', 'EPISODE']
    .map(value => ({label: this.sequenceNumberTypeLabel(value as DownloadSequenceNumberType), value: value as DownloadSequenceNumberType}));
  languageOptions: SelectOption<string | null>[] = [
    {label: this.t.translate('downloads.languages.auto'), value: null},
    {label: this.t.translate('downloads.languages.fr'), value: 'fr'},
    {label: this.t.translate('downloads.languages.en'), value: 'en'},
    {label: this.t.translate('downloads.languages.es'), value: 'es'},
    {label: this.t.translate('downloads.languages.it'), value: 'it'},
    {label: this.t.translate('downloads.languages.de'), value: 'de'},
    {label: this.t.translate('downloads.languages.zh'), value: 'zh'},
    {label: this.t.translate('downloads.languages.ja'), value: 'ja'},
    {label: this.t.translate('downloads.languages.ko'), value: 'ko'}
  ];

  private pollSub?: Subscription;
  private searchProgressTimer?: ReturnType<typeof setInterval>;
  private searchStartedAt = 0;
  private canonicalSearchSignature: string | null = null;
  private canonicalCandidateSearchSignature: string | null = null;
  private lastNoCanonicalSearchSignature: string | null = null;
  private canonicalAppliedState: CanonicalAppliedState | null = null;

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
    this.invalidateCanonicalLockIfQueryChanged();
    const request = this.buildSearchRequest();
    if (!request.query && !request.title && !request.isbn && !request.directUrl) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('downloads.toast.searchRequiredSummary'),
        detail: this.t.translate('downloads.toast.searchRequiredDetail')
      });
      return;
    }

    const requestSignature = this.requestSignature(request);
    if (!this.selectedCanonicalCandidate && this.canonicalCandidates.length && this.canonicalCandidateSearchSignature === requestSignature) {
      this.messageService.add({
        severity: 'warn',
        summary: this.t.translate('downloads.resolve.selectionRequiredSummary'),
        detail: this.t.translate('downloads.resolve.selectionRequiredDetail')
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
        this.allResults = [...(response.results ?? [])]
          .sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
        this.applyResultVisibility();
        if (!this.results.length) {
          this.messageService.add({
            severity: this.searchError ? 'warn' : 'info',
            summary: this.t.translate('downloads.toast.noResultsSummary'),
            detail: this.searchError || (this.hiddenWeakResultCount
              ? this.t.translate('downloads.toast.onlyWeakResultsDetail', {count: this.hiddenWeakResultCount, threshold: this.weakResultThreshold})
              : this.t.translate('downloads.toast.noResultsDetail'))
          });
        }
        this.markViewDirty();
      },
      error: err => {
        this.searchError = err?.error?.message || err?.message || this.t.translate('downloads.toast.searchError');
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.searchError ?? this.t.translate('downloads.toast.searchError')
        });
        this.markViewDirty();
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
        if (job.status !== 'QUEUED') {
          this.loadJobs(false);
          this.showJobToast(job);
          this.markViewDirty();
          return of(job);
        }
        this.processingJobIds.add(job.id);
        this.loadJobs(false);
        this.markViewDirty();
        this.messageService.add({
          severity: 'info',
          summary: this.t.translate('downloads.toast.jobQueuedSummary'),
          detail: this.t.translate('downloads.toast.jobQueuedDetail', {id: job.id})
        });
        return this.downloadsService.processJob(job.id).pipe(
          finalize(() => {
            this.processingJobIds.delete(job.id);
            this.markViewDirty();
          })
        );
      }),
      finalize(() => {
        this.acquiringResultIds.delete(result.id);
        this.markViewDirty();
      })
    ).subscribe({
      next: job => {
        this.loadJobs(false);
        this.showJobToast(job);
        this.markViewDirty();
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.acquireError')
        });
        this.markViewDirty();
      }
    });
  }

  processJob(job: DownloadJob): void {
    if (this.processingJobIds.has(job.id)) return;
    this.processingJobIds.add(job.id);
    this.downloadsService.processJob(job.id).pipe(
      finalize(() => {
        this.processingJobIds.delete(job.id);
        this.markViewDirty();
      })
    ).subscribe({
      next: processed => {
        this.loadJobs(false);
        this.showJobToast(processed);
        this.markViewDirty();
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.processError')
        });
        this.markViewDirty();
      }
    });
  }

  private resolveBeforeSearch(request: DownloadSearchRequest): void {
    const requestSignature = this.requestSignature(request);
    this.resolvingCanonical = true;
    this.searchError = null;
    this.downloadsService.resolve(request).pipe(
      finalize(() => {
        this.resolvingCanonical = false;
        this.markViewDirty();
      })
    ).subscribe({
      next: candidates => {
        this.canonicalCandidates = candidates ?? [];
        if (this.canonicalCandidates.length) {
          this.canonicalCandidateSearchSignature = requestSignature;
          this.lastNoCanonicalSearchSignature = null;
          this.results = [];
          this.allResults = [];
          this.hiddenWeakResultCount = 0;
          this.searchId = null;
          this.messageService.add({
            severity: 'info',
            summary: this.t.translate('downloads.resolve.title'),
            detail: this.t.translate('downloads.resolve.description')
          });
          this.markViewDirty();
          return;
        }
        this.canonicalCandidateSearchSignature = null;
        this.lastNoCanonicalSearchSignature = requestSignature;
        this.messageService.add({
          severity: 'info',
          summary: this.t.translate('downloads.resolve.noneSummary'),
          detail: this.t.translate('downloads.resolve.noneThenSearchDetail')
        });
        this.runSourceSearch(request);
        this.markViewDirty();
      },
      error: err => {
        this.searchError = err?.error?.message || err?.message || this.t.translate('downloads.resolve.error');
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: this.searchError ?? this.t.translate('downloads.resolve.error')
        });
        this.markViewDirty();
      }
    });
  }

  resolveCanonical(): void {
    const request = this.buildSearchRequest(false);
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

    this.clearCanonicalLock();
    this.resolvingCanonical = true;
    this.downloadsService.resolve(request).pipe(
      finalize(() => {
        this.resolvingCanonical = false;
        this.markViewDirty();
      })
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
        this.markViewDirty();
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.resolve.error')
        });
        this.markViewDirty();
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
    this.selectedCanonicalCandidate = candidate;
    this.canonicalCandidates = [];
    this.canonicalCandidateSearchSignature = null;
    this.lastNoCanonicalSearchSignature = null;
    this.canonicalSearchSignature = this.requestSignature(this.buildSearchRequest(false));
    this.canonicalAppliedState = this.captureCanonicalAppliedState();
    this.messageService.add({
      severity: 'success',
      summary: this.t.translate('downloads.resolve.appliedSummary'),
      detail: this.t.translate('downloads.resolve.appliedDetail', {title: this.canonicalCandidateTitle(candidate)})
    });
    this.markViewDirty();
    this.search();
  }

  openCanonicalDetails(candidate: DownloadCanonicalCandidate): void {
    this.canonicalDetailsCandidate = candidate;
    this.markViewDirty();
  }

  closeCanonicalDetails(): void {
    this.canonicalDetailsCandidate = null;
    this.markViewDirty();
  }

  retryJob(job: DownloadJob): void {
    if (!this.canRetry(job) || this.retryingJobIds.has(job.id)) return;
    this.retryingJobIds.add(job.id);
    this.downloadsService.retryJob(job.id).pipe(
      finalize(() => {
        this.retryingJobIds.delete(job.id);
        this.markViewDirty();
      })
    ).subscribe({
      next: retried => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'info',
          summary: this.t.translate('downloads.toast.jobRetrySummary'),
          detail: this.t.translate('downloads.toast.jobRetryDetail', {oldId: job.id, id: retried.id})
        });
        this.showJobToast(retried);
        this.markViewDirty();
      },
      error: err => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.retryError')
        });
        this.markViewDirty();
      }
    });
  }

  archiveJob(job: DownloadJob): void {
    if (!this.canArchive(job) || this.archivingJobIds.has(job.id)) return;
    this.archivingJobIds.add(job.id);
    this.downloadsService.archiveJob(job.id).pipe(
      finalize(() => {
        this.archivingJobIds.delete(job.id);
        this.markViewDirty();
      })
    ).subscribe({
      next: () => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('downloads.toast.jobArchivedDetail', {id: job.id})
        });
        this.markViewDirty();
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || err?.message || this.t.translate('downloads.toast.archiveError')
        });
        this.markViewDirty();
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
        this.markViewDirty();
      },
      error: () => {
        this.loadingJobs = false;
        this.markViewDirty();
      }
    });
  }

  cleanup(): void {
    this.cleanupRunning = true;
    this.downloadsService.cleanupNow().pipe(
      finalize(() => {
        this.cleanupRunning = false;
        this.markViewDirty();
      })
    ).subscribe({
      next: () => {
        this.loadJobs(false);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('downloads.toast.cleanupDone')
        });
        this.markViewDirty();
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || this.t.translate('downloads.toast.cleanupError')
        });
        this.markViewDirty();
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
    this.seriesNumberEnd = null;
    this.preferredLanguage = 'fr';
    this.sequenceNumberType = 'AUTO';
    this.directUrl = '';
    this.contentKind = 'AUTO';
    this.preferredFormats = [...DOWNLOAD_FORMATS];
    this.maxResults = 25;
    this.results = [];
    this.allResults = [];
    this.hiddenWeakResultCount = 0;
    this.showWeakResults = false;
    this.canonicalCandidates = [];
    this.selectedCanonicalCandidate = null;
    this.canonicalSearchSignature = null;
    this.canonicalCandidateSearchSignature = null;
    this.lastNoCanonicalSearchSignature = null;
    this.canonicalAppliedState = null;
    this.searchId = null;
    this.searchError = null;
    this.markViewDirty();
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

  toggleWeakResults(): void {
    this.showWeakResults = !this.showWeakResults;
    this.applyResultVisibility();
    this.markViewDirty();
  }

  resultQualityBadges(result: DownloadResult): ResultQualityBadge[] {
    const reasons = (result.scoreReasons || '').toLowerCase();
    const title = (result.title || '').toLowerCase();
    const badges: ResultQualityBadge[] = [];
    const add = (labelKey: string, tooltipKey: string, severity: ResultQualityBadge['severity']) => {
      if (!badges.some(badge => badge.labelKey === labelKey)) {
        badges.push({labelKey, tooltipKey, severity});
      }
    };

    if ((result.score ?? 0) < this.weakResultThreshold) {
      add('downloads.quality.weakScore', 'downloads.qualityTooltips.weakScore', 'danger');
    }
    if (reasons.includes('bundled range') || reasons.includes('pack') || this.titleLooksLikeBundle(title)) {
      add('downloads.quality.packDetected', 'downloads.qualityTooltips.packDetected', 'warn');
    }
    if (reasons.includes('chapter/episode result for volume/issue request') || reasons.includes('chapter result for volume') || reasons.includes('episode result for volume')) {
      add('downloads.quality.chapterIncompatible', 'downloads.qualityTooltips.chapterIncompatible', 'danger');
    }
    if (reasons.includes('requested volume number mismatch')
      || reasons.includes('requested issue number mismatch')
      || reasons.includes('requested chapter number mismatch')
      || reasons.includes('requested episode number mismatch')
      || reasons.includes('missing requested volume number')
      || reasons.includes('missing requested issue number')
      || reasons.includes('missing requested chapter number')
      || reasons.includes('missing requested episode number')
      || reasons.includes('conflicting requested volume')
      || reasons.includes('conflicting requested issue')
      || reasons.includes('conflicting requested chapter')
      || reasons.includes('conflicting requested episode')
      || reasons.includes('wrong number')) {
      add('downloads.quality.wrongNumber', 'downloads.qualityTooltips.wrongNumber', 'warn');
    }
    if (reasons.includes('unsupported media payload') || reasons.includes('.mkv') || reasons.includes('.mp4') || reasons.includes('1080p') || reasons.includes('bdrip') || reasons.includes('hevc') || reasons.includes('x264')) {
      add('downloads.quality.unsupportedMedia', 'downloads.qualityTooltips.unsupportedMedia', 'danger');
    }
    if (reasons.includes('preferred language mismatch') || reasons.includes('script mismatch')) {
      add('downloads.quality.languageMismatch', 'downloads.qualityTooltips.languageMismatch', 'warn');
    }
    if ((result.acquisitionType === 'TORRENT' || result.acquisitionType === 'NZB') && result.format === 'UNKNOWN') {
      add('downloads.quality.deferredFormat', 'downloads.qualityTooltips.deferredFormat', 'info');
    }

    return badges;
  }

  private titleLooksLikeBundle(title: string): boolean {
    return /\b(?:all|complete|collection|batch|pack|omnibus|int[eé]grale?)\b.{0,80}\b(?:volumes?|tomes?|chapters?|chapitres?|manga)\b/i.test(title)
      || /\b(?:vol(?:ume)?s?|tomes?|v|ch(?:apter)?s?)\s*0?\d{1,4}\s*(?:-|–|—|à|a|to|\+)\s*0?\d{1,4}\b/i.test(title)
      || /\b0?\d{1,4}\s*(?:-|–|—|à|a|to|\+)\s*0?\d{1,4}\s*(?:vol(?:ume)?s?|tomes?|chapters?|manga)\b/i.test(title);
  }

  canAcquireResult(result: DownloadResult): boolean {
    return !this.acquiringResultIds.has(result.id) && (result.score ?? 0) >= this.weakResultThreshold;
  }

  acquireDisabledReason(result: DownloadResult): string {
    if (this.acquiringResultIds.has(result.id)) {
      return '';
    }
    if ((result.score ?? 0) < this.weakResultThreshold) {
      return this.t.translate('downloads.actions.acquireDisabledLowScore', {threshold: this.weakResultThreshold});
    }
    return '';
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

  sequenceNumberTypeLabel(type: DownloadSequenceNumberType): string {
    return this.t.translate(`downloads.sequenceNumberTypes.${type}`);
  }

  canonicalCandidateTitle(candidate: DownloadCanonicalCandidate): string {
    return candidate.resolvedSeriesName || candidate.seriesName || candidate.resolvedTitle || candidate.title || candidate.query || '-';
  }

  canonicalCandidatePrimary(candidate: DownloadCanonicalCandidate): string {
    return candidate.resolvedTitle || candidate.title || candidate.resolvedSeriesName || candidate.seriesName || candidate.query || '-';
  }

  canonicalCandidateSecondary(candidate: DownloadCanonicalCandidate): string {
    const series = candidate.resolvedSeriesName || candidate.seriesName;
    const author = candidate.resolvedAuthor || candidate.author;
    return [series && series !== this.canonicalCandidatePrimary(candidate) ? series : null, author].filter(Boolean).join(' · ');
  }

  canonicalCandidateMeta(candidate: DownloadCanonicalCandidate): string {
    const number = candidate.seriesNumber == null
      ? null
      : `${candidate.sequenceNumberType && candidate.sequenceNumberType !== 'AUTO' ? this.sequenceNumberTypeLabel(candidate.sequenceNumberType) : '#'} ${candidate.seriesNumber}`;
    return [
      candidate.provider,
      this.contentKindLabel(candidate.contentKind),
      candidate.resolvedAuthor || candidate.author,
      candidate.resolvedIsbn || candidate.isbn ? `ISBN ${candidate.resolvedIsbn || candidate.isbn}` : null,
      number
    ].filter(Boolean).join(' · ');
  }

  canonicalCandidateDetails(candidate: DownloadCanonicalCandidate): {label: string; value: string}[] {
    const details = [
      {label: this.t.translate('downloads.resolve.provider'), value: candidate.provider},
      {label: this.t.translate('downloads.resolve.kind'), value: this.contentKindLabel(candidate.contentKind)},
      {label: this.t.translate('downloads.resolve.titleLabel'), value: candidate.resolvedTitle || candidate.title || ''},
      {label: this.t.translate('downloads.resolve.seriesLabel'), value: candidate.resolvedSeriesName || candidate.seriesName || ''},
      {label: this.t.translate('downloads.resolve.authorLabel'), value: candidate.resolvedAuthor || candidate.author || ''},
      {label: this.t.translate('downloads.resolve.isbnLabel'), value: candidate.resolvedIsbn || candidate.isbn || ''},
      {label: this.t.translate('downloads.resolve.yearLabel'), value: candidate.year || ''},
      {label: this.t.translate('downloads.resolve.sequenceLabel'), value: this.canonicalSequenceLabel(candidate)}
    ];
    return details.filter(detail => !!detail.value);
  }

  canonicalSequenceLabel(candidate: DownloadCanonicalCandidate): string {
    if (candidate.seriesNumber == null) {
      return '';
    }
    const type = candidate.sequenceNumberType && candidate.sequenceNumberType !== 'AUTO'
      ? this.sequenceNumberTypeLabel(candidate.sequenceNumberType)
      : '#';
    return `${type} ${candidate.seriesNumber}`;
  }

  resultSequenceLabel(result: DownloadResult): string {
    if (result.seriesNumber == null) {
      return '';
    }
    const formattedNumber = this.formatSeriesNumber(result.seriesNumber);
    if (result.acquisitionType === 'MANGADEX_CHAPTER') {
      return `${this.sequenceNumberTypeLabel('CHAPTER')} ${formattedNumber}`;
    }
    if (result.contentKind === 'WEBTOON') {
      return `${this.sequenceNumberTypeLabel('EPISODE')} ${formattedNumber}`;
    }
    if (result.contentKind === 'COMIC') {
      return `${this.sequenceNumberTypeLabel('ISSUE')} ${formattedNumber}`;
    }
    if (result.contentKind === 'MANGA') {
      return `${this.sequenceNumberTypeLabel('VOLUME')} ${formattedNumber}`;
    }
    return `# ${formattedNumber}`;
  }

  resultHeading(result: DownloadResult): string {
    if (!this.isVisualResult(result) || !result.seriesName) {
      return result.title;
    }
    const sequence = this.resultSequenceLabel(result);
    return sequence ? `${result.seriesName} — ${sequence}` : result.seriesName;
  }

  canonicalExtraEntries(candidate: DownloadCanonicalCandidate): {key: string; value: string}[] {
    return Object.entries(candidate.extraMetadata ?? {})
      .filter((entry): entry is [string, string] => typeof entry[1] === 'string' && !!entry[1].trim())
      .map(([key, value]) => ({key, value}));
  }

  canonicalConfidence(candidate: DownloadCanonicalCandidate): string {
    return `${Math.round((candidate.confidence ?? 0) * 100)}%`;
  }

  isCanonicalLocked(): boolean {
    return this.isCanonicalSelectionCurrent(this.buildSearchRequest(false));
  }

  clearCanonicalLock(): void {
    this.selectedCanonicalCandidate = null;
    this.canonicalSearchSignature = null;
    this.canonicalCandidateSearchSignature = null;
    this.lastNoCanonicalSearchSignature = null;
    this.canonicalAppliedState = null;
    this.markViewDirty();
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

  private applyResultVisibility(): void {
    this.hiddenWeakResultCount = this.allResults.filter(result => (result.score ?? 0) < this.weakResultThreshold).length;
    this.results = this.showWeakResults
      ? [...this.allResults]
      : this.allResults.filter(result => (result.score ?? 0) >= this.weakResultThreshold);
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

  canArchive(job: DownloadJob): boolean {
    return !this.archivingJobIds.has(job.id) && this.isTerminalStatus(job.status);
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

  onQueryChange(value: string): void {
    this.query = value;
    this.invalidateCanonicalLockIfQueryChanged();
  }

  private buildSearchRequest(includeCanonicalSelection = true): DownloadSearchRequest {
    const request: DownloadSearchRequest = {
      query: this.clean(this.query),
      title: this.clean(this.title),
      author: this.clean(this.author),
      isbn: this.clean(this.isbn),
      seriesName: this.clean(this.seriesName),
      seriesNumber: this.seriesNumber,
      seriesNumberEnd: this.seriesNumberEnd,
      preferredLanguage: this.preferredLanguage,
      sequenceNumberType: this.sequenceNumberType,
      directUrl: this.clean(this.directUrl),
      contentKind: this.contentKind,
      preferredFormats: this.preferredFormats,
      maxResults: Math.max(1, Math.min(100, this.maxResults || 25))
    };
    if (includeCanonicalSelection && !request.directUrl && this.selectedCanonicalCandidate && this.isCanonicalSelectionCurrent(request)) {
      request.canonicalSelection = this.toCanonicalSelection(this.selectedCanonicalCandidate);
    }
    return request;
  }

  private clean(value: string): string | null {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }

  private shouldResolveBeforeSearch(request: DownloadSearchRequest): boolean {
    if (request.directUrl) {
      if (this.selectedCanonicalCandidate) {
        this.clearCanonicalLock();
      }
      return false;
    }
    if (!request.query && !request.title && !request.isbn && !request.seriesName) {
      return false;
    }
    if (this.isCanonicalSelectionCurrent(request)) {
      return false;
    }
    if (this.selectedCanonicalCandidate) {
      this.clearCanonicalLock();
    }
    return this.lastNoCanonicalSearchSignature !== this.requestSignature(request);
  }

  private isCanonicalSelectionCurrent(request: DownloadSearchRequest): boolean {
    return this.selectedCanonicalCandidate !== null
      && this.canonicalSearchSignature === this.requestSignature(request);
  }

  private toCanonicalSelection(candidate: DownloadCanonicalCandidate): DownloadCanonicalSelection {
    return {
      provider: candidate.provider,
      contentKind: candidate.contentKind,
      title: candidate.title ?? null,
      author: candidate.author ?? null,
      isbn: candidate.isbn ?? null,
      seriesName: candidate.seriesName ?? null,
      confidence: candidate.confidence,
      query: candidate.query ?? null,
      resolvedTitle: candidate.resolvedTitle ?? null,
      resolvedAuthor: candidate.resolvedAuthor ?? null,
      resolvedIsbn: candidate.resolvedIsbn ?? null,
      resolvedSeriesName: candidate.resolvedSeriesName ?? null,
      seriesNumber: candidate.seriesNumber ?? null,
      sequenceNumberType: candidate.sequenceNumberType ?? 'AUTO',
      coverUrl: candidate.coverUrl ?? null
    };
  }

  private requestSignature(request: DownloadSearchRequest): string {
    return JSON.stringify({
      query: request.query ?? null,
      title: request.title ?? null,
      author: request.author ?? null,
      isbn: request.isbn ?? null,
      seriesName: request.seriesName ?? null,
      seriesNumber: request.seriesNumber ?? null,
      seriesNumberEnd: request.seriesNumberEnd ?? null,
      preferredLanguage: request.preferredLanguage ?? null,
      sequenceNumberType: request.sequenceNumberType ?? 'AUTO',
      directUrl: request.directUrl ?? null,
      contentKind: request.contentKind ?? 'AUTO'
    });
  }

  private captureCanonicalAppliedState(): CanonicalAppliedState {
    return {
      query: this.clean(this.query),
      title: this.title,
      author: this.author,
      isbn: this.isbn,
      seriesName: this.seriesName,
      seriesNumber: this.seriesNumber,
      seriesNumberEnd: this.seriesNumberEnd,
      preferredLanguage: this.preferredLanguage,
      sequenceNumberType: this.sequenceNumberType,
      contentKind: this.contentKind
    };
  }

  private invalidateCanonicalLockIfQueryChanged(): void {
    if (!this.selectedCanonicalCandidate || !this.canonicalAppliedState) {
      return;
    }
    if (this.clean(this.query) === this.canonicalAppliedState.query) {
      return;
    }
    const applied = this.canonicalAppliedState;
    if (this.title === applied.title) this.title = '';
    if (this.author === applied.author) this.author = '';
    if (this.isbn === applied.isbn) this.isbn = '';
    if (this.seriesName === applied.seriesName) this.seriesName = '';
    if (this.seriesNumber === applied.seriesNumber) this.seriesNumber = null;
    if (this.seriesNumberEnd === applied.seriesNumberEnd) this.seriesNumberEnd = null;
    if (this.preferredLanguage === applied.preferredLanguage) this.preferredLanguage = 'fr';
    if (this.sequenceNumberType === applied.sequenceNumberType) this.sequenceNumberType = 'AUTO';
    if (this.contentKind === applied.contentKind) this.contentKind = 'AUTO';
    this.selectedCanonicalCandidate = null;
    this.canonicalSearchSignature = null;
    this.canonicalCandidateSearchSignature = null;
    this.lastNoCanonicalSearchSignature = null;
    this.canonicalAppliedState = null;
    this.results = [];
    this.allResults = [];
    this.hiddenWeakResultCount = 0;
    this.showWeakResults = false;
    this.searchId = null;
    this.searchError = null;
    this.markViewDirty();
  }

  private formatSeriesNumber(value: number): string {
    return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
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
      this.markViewDirty();
    }, 500);
    this.markViewDirty();
  }

  private stopSearchProgress(): void {
    if (this.searchStartedAt) {
      this.lastSearchDurationMs = Date.now() - this.searchStartedAt;
    }
    this.loadingResults = false;
    this.clearSearchProgressTimer();
    this.markViewDirty();
  }

  private clearSearchProgressTimer(): void {
    if (this.searchProgressTimer) {
      clearInterval(this.searchProgressTimer);
      this.searchProgressTimer = undefined;
    }
  }

  private markViewDirty(): void {
    this.cdr.markForCheck();
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
