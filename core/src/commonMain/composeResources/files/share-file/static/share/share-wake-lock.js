(function(global) {
  function normalizeCheckboxes(options) {
    if (Array.isArray(options.checkboxes)) {
      return options.checkboxes.filter(Boolean);
    }
    return options.checkbox ? [options.checkbox] : [];
  }

  function createController(options) {
    const config = options || {};
    const checkboxes = normalizeCheckboxes(config);
    const messages = config.messages || {};
    const notify = typeof config.notify === "function" ? config.notify : function() {};
    const supported = !!(global.navigator && global.navigator.wakeLock && global.navigator.wakeLock.request);
    let active = false;
    let sentinel = null;
    let pendingRequest = null;

    const isChecked = () => checkboxes.some((checkbox) => checkbox.checked);

    const setChecked = (checked) => {
      checkboxes.forEach((checkbox) => {
        checkbox.checked = checked;
        checkbox.setAttribute("aria-checked", checked ? "true" : "false");
      });
    };

    const releaseSentinel = async () => {
      const current = sentinel;
      sentinel = null;
      if (current && !current.released && typeof current.release === "function") {
        await current.release().catch(function() {});
      }
    };

    const acquire = async () => {
      if (!active || !isChecked() || sentinel || pendingRequest) return;
      if (!supported) {
        setChecked(false);
        notify(messages.unsupported || "当前浏览器不支持屏幕常亮");
        return;
      }
      pendingRequest = global.navigator.wakeLock.request("screen")
        .then((lock) => {
          pendingRequest = null;
          if (!active || !isChecked()) {
            if (lock && typeof lock.release === "function") {
              lock.release().catch(function() {});
            }
            return;
          }
          sentinel = lock;
          if (sentinel && typeof sentinel.addEventListener === "function") {
            sentinel.addEventListener("release", function() {
              if (sentinel === lock) {
                sentinel = null;
              }
            });
          }
        })
        .catch(() => {
          pendingRequest = null;
          setChecked(false);
          notify(messages.requestFailed || "屏幕常亮申请失败");
        });
      await pendingRequest;
    };

    const release = async (resetChecked) => {
      if (resetChecked) {
        setChecked(false);
      }
      await releaseSentinel();
    };

    checkboxes.forEach((checkbox) => {
      checkbox.addEventListener("change", function() {
        const checked = checkbox.checked;
        setChecked(checked);
        if (!active) return;
        if (checked) {
          acquire();
        } else {
          release(false);
        }
      });
    });
    setChecked(isChecked());

    global.document.addEventListener("visibilitychange", function() {
      if (global.document.visibilityState === "visible" && active && isChecked() && !sentinel) {
        acquire();
      }
    });

    return {
      begin: function() {
        active = true;
        acquire();
      },
      end: function() {
        active = false;
        release(true);
      },
      setEnabled: setChecked,
      isEnabled: isChecked
    };
  }

  global.FolderSpanWakeLock = {
    createController: createController
  };
})(window);
