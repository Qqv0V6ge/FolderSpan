document.addEventListener("DOMContentLoaded", function() {
  initBrowserPreviewActions();
  initBatchDownloadModal();
});

const ZIP_DOWNLOAD_SCRIPT_URL = "/static/share/share-zip-download.js";
const HTTPS_CONSENT_STORAGE_KEY = "FolderSpan.LinkShare.HttpsConsentKey";
const ZIP_DOWNLOAD_AUTO_START_QUERY = "fm_zip_download";

function initBrowserPreviewActions() {
  const actions = document.querySelectorAll("a.file-card__open[data-preview-mime]");
  actions.forEach((action) => {
    const contentType = action.getAttribute("data-preview-mime") || "";
    if (!browserSupportsLinkSharePreview(contentType)) {
      action.remove();
    }
  });
}

function browserSupportsLinkSharePreview(contentType) {
  const mimeType = String(contentType).split(";", 1)[0].trim().toLowerCase();
  if (!mimeType) return false;

  if (mimeType === "application/pdf" && typeof navigator.pdfViewerEnabled === "boolean") {
    return navigator.pdfViewerEnabled;
  }

  const mediaKind = mimeType.startsWith("audio/")
    ? "audio"
    : mimeType.startsWith("video/") ? "video" : "";
  if (!mediaKind) return true;

  const media = document.createElement(mediaKind);
  if (!media || typeof media.canPlayType !== "function") return true;
  return media.canPlayType(mimeType) !== "";
}

function initBatchDownloadModal() {
  const floatingBtn = document.getElementById("floatingBatchDownloadBtn");
  const modalOverlay = document.getElementById("batchDownloadModal");
  const modalDialog = modalOverlay ? modalOverlay.querySelector(".modal") : null;
  const closeBtn = document.getElementById("closeModal");
  const downloadBtn = document.getElementById("downloadScript");
  const openZipBtn = document.getElementById("openZipDownloadModal");
  const zipOverlay = document.getElementById("zipDownloadModal");
  const zipDialog = zipOverlay ? zipOverlay.querySelector(".modal") : null;
  const closeZipBtn = document.getElementById("closeZipDownloadModal");
  const startStreamBtn = document.getElementById("startStreamBatchDownload");
  const cancelStreamBtn = document.getElementById("cancelStreamBatchDownload");
  const statusEl = document.getElementById("zipDownloadStatus");
  const progressEl = document.getElementById("zipDownloadProgress");
  const progressBar = document.getElementById("zipDownloadProgressBar");
  const zipKeepAwakeToggles = [
    document.getElementById("zipKeepAwakeActiveToggle")
  ].filter(Boolean);
  const httpsConsentOverlay = document.getElementById("httpsConsentModal");
  const httpsConsentDialog = httpsConsentOverlay ? httpsConsentOverlay.querySelector(".modal") : null;
  const declineHttpsBtn = document.getElementById("declineHttpsDownload");
  const continueHttpsBtn = document.getElementById("continueHttpsDownload");

  if (!floatingBtn || !modalOverlay || !modalDialog || !closeBtn) {
    return;
  }

  const i18n = window.I18N || {};
  const messages = i18n.download || {};
  const wakeLockMessages = i18n.wakeLock || {};
  const config = readLinkSharePageConfig();
  const requestUploadBtn = document.getElementById("requestUploadPermission");
  const tabs = Array.from(modalOverlay.querySelectorAll(".tab"));
  const panels = Array.from(modalOverlay.querySelectorAll(".script-panel"));
  const focusableSelectors = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

  let previouslyFocusedElement = null;
  let zipPreviouslyFocusedElement = null;
  let httpsConsentPreviouslyFocusedElement = null;
  let currentTab = "";
  let currentExt = "";
  let zipDownloadModulePromise = null;
  let activeDownload = null;
  let uploadPermissionPollTimer = 0;

  const showSnackbar = (message) => {
    let snackbar = document.getElementById("uploadSnackbar");
    if (!snackbar) {
      snackbar = document.createElement("div");
      snackbar.id = "uploadSnackbar";
      snackbar.className = "snackbar";
      snackbar.innerHTML = '<span class="snackbar__text"></span>';
      document.body.appendChild(snackbar);
    }

    const text = snackbar.querySelector(".snackbar__text");
    if (text) text.textContent = message;
    snackbar.classList.add("snackbar--visible");
    window.clearTimeout(snackbar._hideTimer);
    snackbar._hideTimer = window.setTimeout(() => {
      snackbar.classList.remove("snackbar--visible");
    }, 3200);
  };

  const requestUploadPermission = async () => {
    if (!requestUploadBtn || requestUploadBtn.disabled) return;
    requestUploadBtn.disabled = true;
    try {
      const response = await fetch(new URL("/api/share/upload-permission/request", config.httpBaseUrl).toString(), {
        method: "POST",
        headers: { "X-API-Request": "true" }
      });
      const message = await response.text();
      showSnackbar(message || (response.ok ? "上传权限请求已提交" : "上传权限请求失败"));
      if (response.ok || response.status === 202) {
        startUploadPermissionPolling();
      }
      if (!response.ok && response.status !== 403) {
        requestUploadBtn.disabled = false;
      }
    } catch (_error) {
      showSnackbar("上传权限请求失败");
      requestUploadBtn.disabled = false;
    }
  };

  const buildUploadPermissionCheckUrl = () => {
    const url = new URL("/api/share/upload-check", config.httpBaseUrl);
    url.searchParams.set("target", window.location.pathname || "/");
    url.searchParams.set("relativePath", ".folderspan-upload-permission-check");
    url.searchParams.set("type", "file");
    url.searchParams.set("size", "0");
    return url.toString();
  };

  const startUploadPermissionPolling = () => {
    if (uploadPermissionPollTimer) return;
    let attempts = 0;
    uploadPermissionPollTimer = window.setInterval(async () => {
      attempts += 1;
      try {
        const response = await fetch(buildUploadPermissionCheckUrl(), {
          method: "GET",
          headers: { "X-API-Request": "true" }
        });
        if (response.ok) {
          window.clearInterval(uploadPermissionPollTimer);
          uploadPermissionPollTimer = 0;
          window.location.reload();
          return;
        }
      } catch (_error) {
        // Keep polling until the short window expires.
      }
      if (attempts >= 20) {
        window.clearInterval(uploadPermissionPollTimer);
        uploadPermissionPollTimer = 0;
        if (requestUploadBtn) {
          requestUploadBtn.disabled = false;
        }
      }
    }, 3000);
  };

  const setStatus = (message) => {
    if (statusEl) {
      statusEl.textContent = message || "";
    }
  };

  if (requestUploadBtn) {
    requestUploadBtn.addEventListener("click", requestUploadPermission);
  }

  const wakeLockController = window.FolderSpanWakeLock
    ? window.FolderSpanWakeLock.createController({
      checkboxes: zipKeepAwakeToggles,
      messages: wakeLockMessages,
      notify: setStatus
    })
    : {
      begin: function() {},
      end: function() {},
      setEnabled: function() {},
      isEnabled: function() { return false; }
    };

  const setProgressVisible = (visible) => {
    if (!progressEl) return;
    progressEl.classList.toggle("hidden", !visible);
    progressEl.setAttribute("aria-hidden", visible ? "false" : "true");
  };

  const setProgress = (value) => {
    if (!progressBar) return;
    const safeValue = Math.max(0, Math.min(100, value));
    progressBar.style.width = safeValue + "%";
  };

  const setDownloading = (isDownloading) => {
    if (startStreamBtn) {
      startStreamBtn.disabled = isDownloading;
    }
    if (cancelStreamBtn) {
      cancelStreamBtn.classList.add("hidden");
      cancelStreamBtn.disabled = true;
    }
    if (closeZipBtn) {
      closeZipBtn.disabled = isDownloading;
    }
    setProgressVisible(isDownloading);
    if (!isDownloading) {
      setProgress(0);
    }
  };

  const setActiveTab = (tab) => {
    if (!tab) {
      currentTab = "";
      currentExt = "";
      return;
    }

    currentTab = tab.getAttribute("data-tab") || "";
    currentExt = tab.getAttribute("data-ext") || "";

    tabs.forEach((t) => {
      const isActive = t === tab;
      t.classList.toggle("tab--active", isActive);
      t.setAttribute("aria-selected", isActive ? "true" : "false");
      t.setAttribute("tabindex", isActive ? "0" : "-1");
    });

    panels.forEach((panel) => {
      const isActive = panel.id === (currentTab ? currentTab + "-script" : "");
      panel.classList.toggle("hidden", !isActive);
      if (isActive) {
        panel.removeAttribute("aria-hidden");
      } else {
        panel.setAttribute("aria-hidden", "true");
      }
    });
  };

  const trapFocus = (dialog, event) => {
    if (event.key !== "Tab") {
      return;
    }

    const focusableElements = dialog.querySelectorAll(focusableSelectors);
    if (focusableElements.length === 0) {
      return;
    }

    const firstElement = focusableElements[0];
    const lastElement = focusableElements[focusableElements.length - 1];

    if (event.shiftKey && document.activeElement === firstElement) {
      event.preventDefault();
      lastElement.focus();
    } else if (!event.shiftKey && document.activeElement === lastElement) {
      event.preventDefault();
      firstElement.focus();
    }
  };

  const trapBatchFocus = (event) => trapFocus(modalDialog, event);
  const trapZipFocus = (event) => {
    if (zipDialog) {
      trapFocus(zipDialog, event);
    }
  };
  const trapHttpsConsentFocus = (event) => {
    if (httpsConsentDialog) {
      trapFocus(httpsConsentDialog, event);
    }
  };

  const openModal = () => {
    previouslyFocusedElement = document.activeElement;
    modalOverlay.classList.remove("hidden");
    modalOverlay.setAttribute("aria-hidden", "false");
    floatingBtn.setAttribute("aria-expanded", "true");
    modalDialog.focus();
    modalDialog.addEventListener("keydown", trapBatchFocus);

    if (tabs.length > 0) {
      const activeTab = tabs.find((tab) => tab.classList.contains("tab--active")) || tabs[0];
      setActiveTab(activeTab);
    }
    (tabs[0] || downloadBtn || openZipBtn || closeBtn).focus();
  };

  const closeModal = (restoreFocus = true) => {
    modalOverlay.classList.add("hidden");
    modalOverlay.setAttribute("aria-hidden", "true");
    floatingBtn.setAttribute("aria-expanded", "false");
    modalDialog.removeEventListener("keydown", trapBatchFocus);

    if (restoreFocus && previouslyFocusedElement instanceof HTMLElement) {
      previouslyFocusedElement.focus();
    }
  };

  const closeZipModal = (restoreFocus = true) => {
    if (!zipOverlay || !zipDialog) return;
    if (activeDownload) return;
    zipOverlay.classList.add("hidden");
    zipOverlay.setAttribute("aria-hidden", "true");
    zipDialog.removeEventListener("keydown", trapZipFocus);

    if (restoreFocus && zipPreviouslyFocusedElement instanceof HTMLElement) {
      zipPreviouslyFocusedElement.focus();
    }
  };

  const closeHttpsConsentModal = (restoreFocus = true) => {
    if (!httpsConsentOverlay || !httpsConsentDialog) return;
    httpsConsentOverlay.classList.add("hidden");
    httpsConsentOverlay.setAttribute("aria-hidden", "true");
    httpsConsentDialog.removeEventListener("keydown", trapHttpsConsentFocus);

    if (restoreFocus && httpsConsentPreviouslyFocusedElement instanceof HTMLElement) {
      httpsConsentPreviouslyFocusedElement.focus();
    }
  };

  const showHttpsConsentModal = () => {
    if (!httpsConsentOverlay || !httpsConsentDialog) {
      rememberHttpsConsent(config);
      navigateToHttps(config, { autoStartZip: true });
      return;
    }
    const batchModalWasOpen = !modalOverlay.classList.contains("hidden");
    httpsConsentPreviouslyFocusedElement = batchModalWasOpen ? floatingBtn : document.activeElement;
    if (batchModalWasOpen) {
      closeModal(false);
    }
    if (zipOverlay && !zipOverlay.classList.contains("hidden")) {
      closeZipModal(false);
    }
    httpsConsentOverlay.classList.remove("hidden");
    httpsConsentOverlay.setAttribute("aria-hidden", "false");
    httpsConsentDialog.focus();
    httpsConsentDialog.addEventListener("keydown", trapHttpsConsentFocus);
    (continueHttpsBtn || declineHttpsBtn || httpsConsentDialog).focus();
  };

  const showZipModal = () => {
    zipPreviouslyFocusedElement = modalOverlay.classList.contains("hidden") ? document.activeElement : floatingBtn;
    if (!modalOverlay.classList.contains("hidden")) {
      closeModal(false);
    }
    if (!zipOverlay || !zipDialog) return;
    zipOverlay.classList.remove("hidden");
    zipOverlay.setAttribute("aria-hidden", "false");
    zipDialog.focus();
    zipDialog.addEventListener("keydown", trapZipFocus);

    if (!activeDownload) {
      setStatus(config.httpsAvailable ? messages.streamZipReady : messages.streamZipHttpsUnavailable);
      (startStreamBtn || closeZipBtn || zipDialog).focus();
    } else {
      zipDialog.focus();
    }
  };

  const openZipModal = () => {
    startStreamDownload();
  };

  const startStreamDownload = async () => {
    if (activeDownload) {
      showZipModal();
      return;
    }
    if (!config.httpsAvailable) {
      showZipModal();
      setStatus(messages.streamZipHttpsUnavailable || "当前平台暂不支持下载 ZIP");
      return;
    }
    if (location.protocol !== "https:" || !window.isSecureContext) {
      showHttpsConsentModal();
      return;
    }
    showZipModal();
    let wakeLockStarted = false;
    try {
      let zipDownload = getLoadedZipDownloadModule();
      if (!zipDownload) {
        zipDownloadModulePromise = loadZipDownloadModule(zipDownloadModulePromise);
        zipDownload = await zipDownloadModulePromise;
      }
      await zipDownload.startStreamedZipDownload({
        config,
        messages,
        setStatus,
        setProgress,
        setDownloading,
        onDownloadStarted: () => {
          wakeLockStarted = true;
          wakeLockController.begin();
        },
        setActiveDownload: (download) => {
          activeDownload = download;
        }
      });
    } catch (error) {
      zipDownloadModulePromise = null;
      const message = error && error.message ? error.message : "";
      const text = messages.streamZipFailed ? messages.streamZipFailed(message) : "打包下载失败";
      setStatus(text + "，可使用脚本下载。");
      setDownloading(false);
      activeDownload = null;
    } finally {
      if (wakeLockStarted) {
        wakeLockController.end();
      }
    }
  };

  floatingBtn.addEventListener("click", openModal);
  closeBtn.addEventListener("click", function() {
    closeModal();
  });
  if (openZipBtn) {
    openZipBtn.addEventListener("click", function(event) {
      event.preventDefault();
      event.stopPropagation();
      openZipModal();
    });
  }
  if (closeZipBtn) {
    closeZipBtn.addEventListener("click", function() {
      closeZipModal();
    });
  }

  modalOverlay.addEventListener("click", function(event) {
    if (event.target === modalOverlay) {
      closeModal();
    }
  });

  if (zipOverlay) {
    zipOverlay.addEventListener("click", function(event) {
      if (event.target === zipOverlay && !activeDownload) {
        closeZipModal();
      }
    });
  }
  if (httpsConsentOverlay) {
    httpsConsentOverlay.addEventListener("click", function(event) {
      if (event.target === httpsConsentOverlay) {
        closeHttpsConsentModal();
      }
    });
  }
  if (declineHttpsBtn) {
    declineHttpsBtn.addEventListener("click", function() {
      closeHttpsConsentModal();
    });
  }
  if (continueHttpsBtn) {
    continueHttpsBtn.addEventListener("click", function() {
      rememberHttpsConsent(config);
      navigateToHttps(config, { autoStartZip: true });
    });
  }
  document.addEventListener("keydown", function(event) {
    if (event.key !== "Escape") {
      return;
    }
    if (httpsConsentOverlay && !httpsConsentOverlay.classList.contains("hidden")) {
      closeHttpsConsentModal();
    } else if (zipOverlay && !zipOverlay.classList.contains("hidden")) {
      if (!activeDownload) {
        closeZipModal();
      }
    } else if (!modalOverlay.classList.contains("hidden")) {
      closeModal();
    }
  });

  tabs.forEach((tab, index) => {
    tab.addEventListener("click", function() {
      setActiveTab(tab);
    });

    tab.addEventListener("keydown", function(event) {
      switch (event.key) {
        case "ArrowRight":
        case "ArrowDown": {
          event.preventDefault();
          if (tabs.length === 0) return;
          const nextIndex = (index + 1) % tabs.length;
          setActiveTab(tabs[nextIndex]);
          tabs[nextIndex].focus();
          break;
        }
        case "ArrowLeft":
        case "ArrowUp": {
          event.preventDefault();
          if (tabs.length === 0) return;
          const prevIndex = (index - 1 + tabs.length) % tabs.length;
          setActiveTab(tabs[prevIndex]);
          tabs[prevIndex].focus();
          break;
        }
        case "Home": {
          event.preventDefault();
          if (tabs.length === 0) return;
          setActiveTab(tabs[0]);
          tabs[0].focus();
          break;
        }
        case "End": {
          event.preventDefault();
          if (tabs.length === 0) return;
          const lastIndex = tabs.length - 1;
          setActiveTab(tabs[lastIndex]);
          tabs[lastIndex].focus();
          break;
        }
        case "Enter":
        case " ": {
          event.preventDefault();
          setActiveTab(tab);
          break;
        }
        default:
          break;
      }
    });
  });

  const initialTab = tabs.find((tab) => tab.classList.contains("tab--active"));
  if (initialTab) {
    setActiveTab(initialTab);
  }

  if (downloadBtn) {
    downloadBtn.addEventListener("click", function() {
      const panelId = currentTab ? currentTab + "-script" : "";
      const scriptElement = panelId ? document.getElementById(panelId) : null;
      const scriptText = scriptElement ? scriptElement.querySelector("pre")?.textContent ?? "" : "";
      const filename = currentExt ? "download_script." + currentExt : "download_script.txt";

      if (!scriptText) {
        return;
      }

      const blob = new Blob([scriptText], { type: "text/plain" });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.style.display = "none";
      a.href = url;
      a.download = filename;

      document.body.appendChild(a);
      a.click();

      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    });
  }

  if (startStreamBtn) {
    startStreamBtn.addEventListener("click", function() {
      startStreamDownload();
    });
  }

  if (cancelStreamBtn) {
    cancelStreamBtn.addEventListener("click", function() {

    });
  }

  const autoStartRequest = consumeZipDownloadAutoStartRequest();
  if (autoStartRequest.autoStart) {
    showZipModal();
  } else {
    setStatus(config.httpsAvailable ? messages.streamZipReady : messages.streamZipHttpsUnavailable);
  }
}

function readLinkSharePageConfig() {
  const dataset = document.body ? document.body.dataset : {};
  return {
    httpBaseUrl: dataset.linkShareHttpBaseUrl || "",
    httpsBaseUrl: dataset.linkShareHttpsBaseUrl || "",
    httpsAvailable: dataset.linkShareHttpsAvailable === "true",
    tlsFingerprint: dataset.linkShareTlsFingerprint || "",
    consentKey: dataset.linkShareHttpsConsentKey || "",
    consentCookie: dataset.linkShareHttpsConsentCookie || "FolderSpanLinkShareHttpsConsent"
  };
}

function rememberHttpsConsent(config) {
  if (!config.consentKey) return;
  try {
    window.localStorage.setItem(HTTPS_CONSENT_STORAGE_KEY, config.consentKey);
  } catch (_) {
    // Some browsers disable storage in private modes; cookie fallback is enough.
  }
  const encoded = encodeURIComponent(config.consentKey);
  document.cookie = config.consentCookie + "=" + encoded + "; Max-Age=2592000; Path=/; SameSite=Lax";
}

function navigateToHttps(config, options) {
  if (!config.httpsBaseUrl) return;
  const nextUrl = new URL(location.pathname + location.search + location.hash, config.httpsBaseUrl);
  if (options && options.autoStartZip === true) {
    nextUrl.searchParams.set(ZIP_DOWNLOAD_AUTO_START_QUERY, "1");
  }
  location.href = nextUrl.toString();
}

function consumeZipDownloadAutoStartRequest() {
  let currentUrl;
  try {
    currentUrl = new URL(location.href);
  } catch (_) {
    return { autoStart: false, keepAwake: false };
  }
  if (currentUrl.searchParams.get(ZIP_DOWNLOAD_AUTO_START_QUERY) !== "1") {
    return { autoStart: false, keepAwake: false };
  }
  currentUrl.searchParams.delete(ZIP_DOWNLOAD_AUTO_START_QUERY);
  if (window.history && typeof window.history.replaceState === "function") {
    const nextPath = currentUrl.pathname + currentUrl.search + currentUrl.hash;
    window.history.replaceState(null, "", nextPath);
  }
  return { autoStart: true, keepAwake: false };
}

function loadZipDownloadModule(existingPromise) {
  if (existingPromise) return existingPromise;
  const loaded = getLoadedZipDownloadModule();
  if (loaded) return Promise.resolve(loaded);
  return loadScript(ZIP_DOWNLOAD_SCRIPT_URL).then(() => {
    const zipDownload = getLoadedZipDownloadModule();
    if (!zipDownload || typeof zipDownload.startStreamedZipDownload !== "function") {
      throw new Error("下载 ZIP 组件不可用");
    }
    return zipDownload;
  });
}

function getLoadedZipDownloadModule() {
  const zipDownload = window.FolderSpanZipDownload;
  return zipDownload && typeof zipDownload.startStreamedZipDownload === "function" ? zipDownload : null;
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector('script[data-dynamic-src="' + src + '"]');
    if (existing && existing.getAttribute("data-loaded") === "true") {
      resolve();
      return;
    }
    if (existing) {
      existing.addEventListener("load", () => resolve(), { once: true });
      existing.addEventListener("error", () => reject(new Error(src)), { once: true });
      return;
    }
    const script = document.createElement("script");
    script.src = src;
    script.async = true;
    script.setAttribute("data-dynamic-src", src);
    script.addEventListener("load", () => {
      script.setAttribute("data-loaded", "true");
      resolve();
    }, { once: true });
    script.addEventListener("error", () => reject(new Error(src)), { once: true });
    document.head.appendChild(script);
  });
}
