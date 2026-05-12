import {Component, inject, OnInit} from '@angular/core';
import {AsyncPipe} from '@angular/common';
import {FormBuilder, FormsModule, ReactiveFormsModule, Validators} from '@angular/forms';
import {Button} from 'primeng/button';
import {InputText} from 'primeng/inputtext';
import {InputNumber} from 'primeng/inputnumber';
import {Select} from 'primeng/select';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {TableModule} from 'primeng/table';
import {Dialog} from 'primeng/dialog';
import {Textarea} from 'primeng/textarea';
import {Tooltip} from 'primeng/tooltip';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {ConfirmationService, MessageService} from 'primeng/api';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {UserService} from '../user-management/user.service';
import {DownloadsService} from '../../downloads/downloads.service';
import {DOWNLOAD_SOURCE_TYPES, DownloadSource, DownloadSourceRequest, DownloadSourceType} from '../../downloads/downloads.model';

interface SourceTypeOption {
  value: DownloadSourceType;
  translationKey: string;
}

@Component({
  selector: 'app-download-sources',
  imports: [
    Button,
    InputText,
    InputNumber,
    Select,
    ToggleSwitch,
    TableModule,
    Dialog,
    Textarea,
    Tooltip,
    ConfirmDialog,
    AsyncPipe,
    FormsModule,
    ReactiveFormsModule,
    TranslocoDirective,
    TranslocoPipe
  ],
  providers: [ConfirmationService],
  templateUrl: './download-sources.component.html',
  styleUrl: './download-sources.component.scss'
})
export class DownloadSourcesComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly downloadsService = inject(DownloadsService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  protected readonly userService = inject(UserService);
  private readonly t = inject(TranslocoService);

  sources: DownloadSource[] = [];
  loading = false;
  sourceDialogVisible = false;
  saving = false;
  editingSource: DownloadSource | null = null;
  flareSolverrEnabled = false;
  flareSolverrBaseUrl = 'http://localhost:8191';

  sourceTypeOptions: SourceTypeOption[] = DOWNLOAD_SOURCE_TYPES.map(type => ({
    value: type,
    translationKey: `settingsDownloadSources.types.${type}`
  }));

  sourceForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    type: ['PROWLARR_TORZNAB' as DownloadSourceType, Validators.required],
    enabled: [true],
    priority: [100, [Validators.required, Validators.min(0), Validators.max(9999)]],
    credentialsJson: [''],
    configJson: ['']
  });

  ngOnInit(): void {
    this.loadSources();
  }

  loadSources(): void {
    this.loading = true;
    this.downloadsService.listSources().subscribe({
      next: sources => {
        this.sources = sources;
        this.loading = false;
      },
      error: err => {
        this.loading = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || this.t.translate('settingsDownloadSources.toast.loadError')
        });
      }
    });
  }

  openCreateDialog(): void {
    this.editingSource = null;
    this.sourceForm.reset({
      name: '',
      type: 'PROWLARR_TORZNAB',
      enabled: true,
      priority: 100,
      credentialsJson: '',
      configJson: ''
    });
    this.applyTypeDefaults('PROWLARR_TORZNAB', true);
    this.sourceDialogVisible = true;
  }

  openEditDialog(source: DownloadSource): void {
    this.editingSource = source;
    this.sourceForm.reset({
      name: source.name,
      type: source.type,
      enabled: source.enabled,
      priority: source.priority ?? 100,
      credentialsJson: this.prettyJsonOrRaw(source.credentialsJson),
      configJson: this.prettyJsonOrRaw(source.configJson)
    });
    this.extractFlareSolverrSettings(source.configJson, source.credentialsJson);
    this.sourceDialogVisible = true;
  }

  onSourceTypeChanged(type: DownloadSourceType): void {
    if (!this.editingSource) {
      this.applyTypeDefaults(type, false);
    }
  }

  saveSource(): void {
    if (this.sourceForm.invalid) {
      this.sourceForm.markAllAsTouched();
      return;
    }

    const credentialsJson = this.normalizeJson(this.sourceForm.controls.credentialsJson.value, 'credentials');
    if (credentialsJson === undefined) return;
    const configJson = this.normalizeConfigJson(this.sourceForm.controls.configJson.value);
    if (configJson === undefined) return;

    const request: DownloadSourceRequest = {
      name: this.sourceForm.controls.name.value!.trim(),
      type: this.sourceForm.controls.type.value!,
      enabled: !!this.sourceForm.controls.enabled.value,
      priority: this.sourceForm.controls.priority.value ?? 100,
      credentialsJson,
      configJson
    };

    this.saving = true;
    const operation = this.editingSource
      ? this.downloadsService.updateSource(this.editingSource.id, request)
      : this.downloadsService.createSource(request);

    operation.subscribe({
      next: () => {
        this.saving = false;
        this.sourceDialogVisible = false;
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate(this.editingSource ? 'settingsDownloadSources.toast.updated' : 'settingsDownloadSources.toast.created')
        });
        this.loadSources();
      },
      error: err => {
        this.saving = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || this.t.translate('settingsDownloadSources.toast.saveError')
        });
      }
    });
  }

  confirmDelete(source: DownloadSource): void {
    this.confirmationService.confirm({
      message: this.t.translate('settingsDownloadSources.delete.message', {name: source.name}),
      header: this.t.translate('settingsDownloadSources.delete.header'),
      icon: 'pi pi-exclamation-triangle',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.deleteSource(source)
    });
  }

  deleteSource(source: DownloadSource): void {
    this.downloadsService.deleteSource(source.id).subscribe({
      next: () => {
        this.sources = this.sources.filter(item => item.id !== source.id);
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('common.success'),
          detail: this.t.translate('settingsDownloadSources.toast.deleted')
        });
      },
      error: err => {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('common.error'),
          detail: err?.error?.message || this.t.translate('settingsDownloadSources.toast.deleteError')
        });
      }
    });
  }

  formatJsonInForm(controlName: 'credentialsJson' | 'configJson'): void {
    const value = this.sourceForm.controls[controlName].value;
    const normalized = this.normalizeJson(value, controlName);
    if (normalized !== undefined) {
      this.sourceForm.controls[controlName].setValue(normalized ?? '');
    }
  }

  typeLabel(type: DownloadSourceType): string {
    return this.t.translate(`settingsDownloadSources.types.${type}`);
  }

  private applyTypeDefaults(type: DownloadSourceType, force: boolean): void {
    const currentCredentials = this.sourceForm.controls.credentialsJson.value?.trim();
    const currentConfig = this.sourceForm.controls.configJson.value?.trim();
    if (force || !currentCredentials) {
      this.sourceForm.controls.credentialsJson.setValue(this.prettyJson(this.defaultCredentials(type)));
    }
    if (force || !currentConfig) {
      this.sourceForm.controls.configJson.setValue(this.prettyJson(this.defaultConfig(type)));
    }
    this.extractFlareSolverrSettings(this.sourceForm.controls.configJson.value, this.sourceForm.controls.credentialsJson.value);
  }

  private normalizeJson(value: string | null | undefined, label: string): string | null | undefined {
    const trimmed = value?.trim();
    if (!trimmed) {
      return null;
    }
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('settingsDownloadSources.toast.invalidJsonSummary'),
        detail: this.t.translate('settingsDownloadSources.toast.invalidJsonDetail', {label})
      });
      return undefined;
    }
  }

  private normalizeConfigJson(value: string | null | undefined): string | null | undefined {
    const normalized = this.normalizeJson(value, 'config');
    if (normalized === undefined) return undefined;
    const root = normalized ? JSON.parse(normalized) : {};
    if (typeof root !== 'object' || Array.isArray(root) || root === null) {
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('settingsDownloadSources.toast.invalidJsonSummary'),
        detail: this.t.translate('settingsDownloadSources.toast.configObjectRequired')
      });
      return undefined;
    }

    const flareSolverr = typeof root.flareSolverr === 'object' && root.flareSolverr !== null && !Array.isArray(root.flareSolverr)
      ? root.flareSolverr
      : {};
    root.flareSolverr = {
      ...flareSolverr,
      enabled: this.flareSolverrEnabled,
      baseUrl: this.flareSolverrBaseUrl?.trim() || 'http://localhost:8191',
      maxTimeoutMs: Number(flareSolverr.maxTimeoutMs ?? 60000)
    };
    return JSON.stringify(root, null, 2);
  }

  private extractFlareSolverrSettings(configJson?: string | null, credentialsJson?: string | null): void {
    const config = this.parseObject(configJson);
    const credentials = this.parseObject(credentialsJson);
    const flareFromConfig = this.objectValue(config?.['flareSolverr']);
    const flareFromCredentials = this.objectValue(credentials?.['flareSolverr']);

    this.flareSolverrEnabled = !!(
      config?.['useFlareSolverr'] ||
      credentials?.['useFlareSolverr'] ||
      flareFromConfig?.['enabled'] ||
      flareFromCredentials?.['enabled']
    );
    this.flareSolverrBaseUrl =
      this.stringValue(flareFromConfig?.['baseUrl']) ||
      this.stringValue(flareFromCredentials?.['baseUrl']) ||
      this.stringValue(config?.['flareSolverrBaseUrl']) ||
      this.stringValue(credentials?.['flareSolverrBaseUrl']) ||
      'http://localhost:8191';
  }

  private defaultCredentials(type: DownloadSourceType): Record<string, unknown> {
    switch (type) {
      case 'PROWLARR_TORZNAB':
        return {
          baseUrl: 'http://localhost:9696',
          apiKey: '',
          indexer: 'all',
          function: 'search',
          categories: '',
          timeoutSeconds: 20
        };
      case 'OPDS':
        return {
          baseUrl: '',
          searchUrlTemplate: '',
          timeoutSeconds: 20
        };
      case 'ANNAS_ARCHIVE_API':
        return {};
      default:
        return {};
    }
  }

  private defaultConfig(type: DownloadSourceType): Record<string, unknown> {
    const flareSolverr = {
      enabled: false,
      baseUrl: 'http://localhost:8191',
      maxTimeoutMs: 60000
    };
    switch (type) {
      case 'PROWLARR_TORZNAB':
        return {
          qbittorrent: {
            baseUrl: 'http://localhost:8080',
            username: '',
            password: '',
            category: 'booklore',
            tags: 'booklore',
            remoteSavePath: '{stagingDir}',
            localSavePath: '{stagingDir}',
            pollIntervalSeconds: 10,
            timeoutMinutes: 180,
            deleteTorrentOnComplete: true,
            deleteFilesOnComplete: true
          },
          flareSolverr
        };
      case 'MANGADEX':
        return {
          mangadex: {
            apiBaseUrl: 'https://api.mangadex.org',
            siteBaseUrl: 'https://mangadex.org',
            translatedLanguage: 'en',
            timeoutSeconds: 30,
            mangaLimit: 3,
            chapterLimitPerManga: 100,
            dataSaver: false
          }
        };
      case 'DIRECT_URL':
        return {
          acquisitionType: 'DIRECT_FILE',
          galleryDl: {
            enabled: false,
            binaryPath: 'gallery-dl',
            timeoutMinutes: 30,
            extraArgs: []
          },
          flareSolverr
        };
      case 'ANNAS_ARCHIVE_API':
        return {
          annasArchiveApi: {
            baseUrl: 'https://annas-archive.gl',
            fallbackBaseUrls: [
              'https://annas-archive.gd',
              'https://annas-archive.pk',
              'https://annas-archive.li'
            ],
            searchPath: '/search',
            acquisitionType: 'EXTERNAL_STACKS',
            queryParam: 'q',
            formatParam: 'ext',
            defaultFormat: 'epub',
            maxResults: 50,
            requiresFlareSolverr: true,
            resultLinkSelector: 'a.js-vim-focus[href*=/md5/], a.font-semibold[href*=/md5/]'
          },
          stacks: {
            baseUrl: 'http://localhost:7788',
            downloadEndpoint: '/api/queue/add',
            statusUrlTemplate: 'http://localhost:7788/api/status',
            apiKey: '',
            apiKeyHeader: 'X-API-Key',
            remoteDownloadRoots: ['/opt/stacks/download', '/bookdrop'],
            localDownloadRoot: '{bookdrop}',
            remoteStagingPath: '{stagingDir}',
            pollIntervalSeconds: 10,
            timeoutMinutes: 180,
            requestTimeoutSeconds: 30
          },
          flareSolverr: {
            ...flareSolverr,
            enabled: true
          }
        };
      case 'CUSTOM_WEB_PLUGIN':
        return {
          pluginClassName: '',
          pluginJarPath: '',
          galleryDl: {
            enabled: false,
            binaryPath: 'gallery-dl',
            timeoutMinutes: 30,
            extraArgs: []
          },
          flareSolverr
        };
      default:
        return {};
    }
  }

  private prettyJson(value: unknown): string {
    return JSON.stringify(value, null, 2);
  }

  private prettyJsonOrRaw(value?: string | null): string {
    if (!value?.trim()) {
      return '';
    }
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }

  private parseObject(value?: string | null): Record<string, unknown> | null {
    if (!value?.trim()) return null;
    try {
      const parsed = JSON.parse(value);
      return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }

  private objectValue(value: unknown): Record<string, unknown> | null {
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
  }

  private stringValue(value: unknown): string | null {
    return typeof value === 'string' && value.trim() ? value.trim() : null;
  }
}
