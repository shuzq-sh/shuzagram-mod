"""exteraGram-compatible Android helpers."""
from java import dynamic_proxy, jclass

_Runnable = jclass("java.lang.Runnable")
_View = jclass("android.view.View")
_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
_Log = jclass("android.util.Log")


class R(dynamic_proxy(_Runnable)):
    """Java Runnable backed by a Python callable."""

    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception:
            import traceback
            _Log.e("ShuzaPlugins", traceback.format_exc())


class OnClickListener(dynamic_proxy(_View.OnClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, view):
        try:
            self.fn(view)
        except Exception:
            import traceback
            _Log.e("ShuzaPlugins", traceback.format_exc())


class OnLongClickListener(dynamic_proxy(_View.OnLongClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, view):
        try:
            return bool(self.fn(view))
        except Exception:
            import traceback
            _Log.e("ShuzaPlugins", traceback.format_exc())
            return False


def run_on_ui_thread(func, delay=0):
    runnable = func if isinstance(func, R) else R(func)
    if delay and delay > 0:
        _AndroidUtilities.runOnUIThread(runnable, int(delay))
    else:
        _AndroidUtilities.runOnUIThread(runnable)
    return runnable


def cancel_run_on_ui_thread(runnable):
    _AndroidUtilities.cancelRunOnUIThread(runnable)


def log(data):
    _Log.d("ShuzaPlugins", str(data))


def copy_to_clipboard(text, show_bulletin=True):
    ok = _AndroidUtilities.addToClipboard(str(text))
    if ok and show_bulletin:
        try:
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_copied_to_clipboard()
        except Exception:
            pass
    return ok


def dp(value):
    return _AndroidUtilities.dp(float(value))


def get_context():
    return jclass("org.telegram.messenger.ApplicationLoader").applicationContext


def vibrate(duration_ms=30):
    try:
        ctx = get_context()
        v = ctx.getSystemService("vibrator")
        v.vibrate(int(duration_ms))
    except Exception:
        pass
