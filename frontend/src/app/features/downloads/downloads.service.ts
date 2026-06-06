import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {API_CONFIG} from '../../core/config/api-config';
import {
  DownloadCanonicalCandidate,
  DownloadAcquireRequest,
  DownloadJob,
  DownloadJobStatus,
  DownloadResultAcquireRequest,
  DownloadSearchRequest,
  DownloadSearchResponse,
  DownloadSource,
  DownloadSourceRequest,
  DownloadSourceTestRequest,
  DownloadSourceTestResponse
} from './downloads.model';

@Injectable({
  providedIn: 'root'
})
export class DownloadsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/v1/downloads`;

  listSources(): Observable<DownloadSource[]> {
    return this.http.get<DownloadSource[]>(`${this.baseUrl}/sources`);
  }

  createSource(request: DownloadSourceRequest): Observable<DownloadSource> {
    return this.http.post<DownloadSource>(`${this.baseUrl}/sources`, request);
  }

  updateSource(sourceId: number, request: DownloadSourceRequest): Observable<DownloadSource> {
    return this.http.put<DownloadSource>(`${this.baseUrl}/sources/${sourceId}`, request);
  }

  deleteSource(sourceId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/sources/${sourceId}`);
  }

  testSource(request: DownloadSourceTestRequest): Observable<DownloadSourceTestResponse> {
    return this.http.post<DownloadSourceTestResponse>(`${this.baseUrl}/sources/test`, request);
  }

  testExistingSource(sourceId: number, request: DownloadSourceTestRequest): Observable<DownloadSourceTestResponse> {
    return this.http.post<DownloadSourceTestResponse>(`${this.baseUrl}/sources/${sourceId}/test`, request);
  }

  search(request: DownloadSearchRequest): Observable<DownloadSearchResponse> {
    return this.http.post<DownloadSearchResponse>(`${this.baseUrl}/search`, request);
  }

  resolve(request: DownloadSearchRequest): Observable<DownloadCanonicalCandidate[]> {
    return this.http.post<DownloadCanonicalCandidate[]>(`${this.baseUrl}/resolve`, request);
  }

  queueBestMatch(request: DownloadAcquireRequest): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/jobs`, request);
  }

  acquireBestMatch(request: DownloadAcquireRequest): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/acquire`, request);
  }

  queueSelectedResult(resultId: number, request: DownloadResultAcquireRequest): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/results/${resultId}/jobs`, request);
  }

  acquireSelectedResult(resultId: number, request: DownloadResultAcquireRequest): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/results/${resultId}/acquire`, request);
  }

  processJob(jobId: number): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/jobs/${jobId}/process`, {});
  }

  retryJob(jobId: number): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/jobs/${jobId}/retry`, {});
  }

  archiveJob(jobId: number): Observable<DownloadJob> {
    return this.http.post<DownloadJob>(`${this.baseUrl}/jobs/${jobId}/archive`, {});
  }

  getJob(jobId: number): Observable<DownloadJob> {
    return this.http.get<DownloadJob>(`${this.baseUrl}/jobs/${jobId}`);
  }

  listJobs(status?: DownloadJobStatus): Observable<DownloadJob[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http.get<DownloadJob[]>(`${this.baseUrl}/jobs`, {params});
  }

  cleanupNow(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/cleanup`, {});
  }
}
