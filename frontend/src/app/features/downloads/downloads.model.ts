export type DownloadSourceType = 'OPDS' | 'PROWLARR_TORZNAB' | 'DIRECT_URL' | 'MANGADEX' | 'ANNAS_ARCHIVE_API' | 'CUSTOM_WEB_PLUGIN';
export type DownloadContentKind = 'AUTO' | 'BOOK' | 'MANGA' | 'COMIC' | 'WEBTOON';
export type DownloadSequenceNumberType = 'AUTO' | 'VOLUME' | 'ISSUE' | 'CHAPTER' | 'EPISODE';
export type DownloadFormat = 'EPUB' | 'PDF' | 'CBZ' | 'CBR' | 'CB7' | 'MOBI' | 'AZW' | 'AZW3' | 'FB2' | 'UNKNOWN';
export type DownloadAcquisitionType =
  | 'DIRECT_FILE'
  | 'TORRENT'
  | 'NZB'
  | 'OPDS_ACQUISITION'
  | 'MANGADEX_CHAPTER'
  | 'IMAGE_SEQUENCE_CBZ'
  | 'CLI_GALLERY_DL'
  | 'EXTERNAL_STACKS'
  | 'WEB_PLUGIN'
  | 'UNKNOWN';
export type DownloadSearchStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type DownloadJobStatus =
  | 'QUEUED'
  | 'SEARCHING'
  | 'SCORING'
  | 'DOWNLOADING'
  | 'VALIDATING'
  | 'STAGED'
  | 'DELIVERING'
  | 'PENDING_REVIEW'
  | 'AUTO_FINALIZING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface DownloadSource {
  id: number;
  name: string;
  type: DownloadSourceType;
  credentialsJson?: string | null;
  configJson?: string | null;
  enabled: boolean;
  priority: number;
}

export interface DownloadSourceRequest {
  name: string;
  type: DownloadSourceType;
  credentialsJson?: string | null;
  configJson?: string | null;
  enabled: boolean;
  priority: number;
}

export interface DownloadSearchRequest {
  query?: string | null;
  title?: string | null;
  author?: string | null;
  isbn?: string | null;
  seriesName?: string | null;
  seriesNumber?: number | null;
  sequenceNumberType?: DownloadSequenceNumberType;
  contentKind?: DownloadContentKind;
  preferredFormats?: DownloadFormat[];
  directUrl?: string | null;
  maxResults?: number;
}

export interface DownloadAcquireRequest extends DownloadSearchRequest {
  targetLibraryId?: number | null;
  targetLibraryPathId?: number | null;
  autoFinalize?: boolean;
  confidenceThreshold?: number;
}

export interface DownloadResultAcquireRequest {
  targetLibraryId?: number | null;
  targetLibraryPathId?: number | null;
  autoFinalize?: boolean;
  confidenceThreshold?: number;
}

export interface DownloadSearchResponse {
  id: number;
  status: DownloadSearchStatus;
  query: string;
  errorMessage?: string | null;
  results: DownloadResult[];
}

export interface DownloadCanonicalCandidate {
  provider: string;
  contentKind: DownloadContentKind;
  title?: string | null;
  author?: string | null;
  isbn?: string | null;
  seriesName?: string | null;
  confidence: number;
  query?: string | null;
  resolvedTitle?: string | null;
  resolvedAuthor?: string | null;
  resolvedIsbn?: string | null;
  resolvedSeriesName?: string | null;
  seriesNumber?: number | null;
  sequenceNumberType?: DownloadSequenceNumberType | null;
}

export interface DownloadResult {
  id: number;
  sourceId: number;
  sourceName: string;
  externalId?: string | null;
  title: string;
  authorsJson?: string | null;
  seriesName?: string | null;
  seriesNumber?: number | null;
  publishedYear?: number | null;
  isbn?: string | null;
  language?: string | null;
  format: DownloadFormat;
  contentKind: DownloadContentKind;
  acquisitionType: DownloadAcquisitionType;
  sizeBytes?: number | null;
  downloadUrl?: string | null;
  detailsUrl?: string | null;
  requiresFlareSolverr: boolean;
  score?: number | null;
  scoreReasons?: string | null;
}

export interface DownloadJob {
  id: number;
  status: DownloadJobStatus;
  progressPercent?: number | null;
  confidenceScore?: number | null;
  externalTaskType?: string | null;
  externalTaskId?: string | null;
  stagingDir?: string | null;
  partFilePath?: string | null;
  stagedFilePath?: string | null;
  deliveredFilePath?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
}

export const DOWNLOAD_SOURCE_TYPES: DownloadSourceType[] = ['PROWLARR_TORZNAB', 'MANGADEX', 'ANNAS_ARCHIVE_API', 'OPDS', 'DIRECT_URL', 'CUSTOM_WEB_PLUGIN'];
export const DOWNLOAD_CONTENT_KINDS: DownloadContentKind[] = ['AUTO', 'BOOK', 'MANGA', 'COMIC', 'WEBTOON'];
export const DOWNLOAD_FORMATS: DownloadFormat[] = ['EPUB', 'PDF', 'CBZ', 'CBR', 'CB7', 'MOBI', 'AZW', 'AZW3', 'FB2'];
