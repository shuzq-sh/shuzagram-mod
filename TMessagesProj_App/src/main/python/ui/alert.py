"""exteraGram-compatible AlertDialogBuilder over Telegram's AlertDialog."""
from typing import Callable, Dict, List, Optional

from java import dynamic_proxy, jclass

_AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
_DialogInterface = jclass("android.content.DialogInterface")
_OnButtonClickListener = jclass("org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener")


class _ButtonListener(dynamic_proxy(_OnButtonClickListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onClick(self, dialog, which):
        if self.fn:
            try:
                self.fn(self.builder, which)
            except Exception:
                import traceback
                traceback.print_exc()


class _DismissListener(dynamic_proxy(_DialogInterface.OnDismissListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onDismiss(self, dialog):
        try:
            self.fn(self.builder)
        except Exception:
            pass


class _CancelListener(dynamic_proxy(_DialogInterface.OnCancelListener)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder = builder
        self.fn = fn

    def onCancel(self, dialog):
        try:
            self.fn(self.builder)
        except Exception:
            pass


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context=None, progress_style: int = 0, resources_provider=None):
        if context is None:
            from client_utils import get_last_fragment
            f = get_last_fragment()
            context = f.getParentActivity() if f is not None else None
        self._builder = _AlertDialog.Builder(context, int(progress_style), resources_provider)
        self._dialog = None
        self._keep = []

    def _listener(self, fn):
        if fn is None:
            return None
        l = _ButtonListener(self, fn)
        self._keep.append(l)
        return l

    def set_title(self, title):
        self._builder.setTitle(str(title))
        return self

    def set_message(self, message):
        self._builder.setMessage(message if not isinstance(message, str) else str(message))
        return self

    def set_message_text_view_clickable(self, clickable):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_view(self, view, height=-2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items: List[str], listener=None, icons: Optional[List[int]] = None):
        CharSequence = jclass("java.lang.CharSequence")
        from java import jarray, jint
        arr = jarray(CharSequence)([str(i) for i in items])
        if icons:
            self._builder.setItems(arr, jarray(jint)(icons), self._listener(listener))
        else:
            self._builder.setItems(arr, self._listener(listener))
        return self

    def set_positive_button(self, text, listener=None):
        self._builder.setPositiveButton(str(text), self._listener(listener))
        return self

    def set_negative_button(self, text, listener=None):
        self._builder.setNegativeButton(str(text), self._listener(listener))
        return self

    def set_neutral_button(self, text, listener=None):
        self._builder.setNeutralButton(str(text), self._listener(listener))
        return self

    def make_button_red(self, button_type):
        self._pending_red = getattr(self, "_pending_red", []) + [int(button_type)]
        return self

    def set_on_back_button_listener(self, listener=None):
        self._builder.setOnBackButtonListener(self._listener(listener))
        return self

    def set_on_dismiss_listener(self, listener=None):
        if listener:
            l = _DismissListener(self, listener)
            self._keep.append(l)
            self._builder.setOnDismissListener(l)
        return self

    def set_on_cancel_listener(self, listener=None):
        if listener:
            l = _CancelListener(self, listener)
            self._keep.append(l)
            self._builder.setOnCancelListener(l)
        return self

    def set_top_image(self, res_id, background_color):
        self._builder.setTopImage(int(res_id), int(background_color))
        return self

    def set_top_drawable(self, drawable, background_color):
        self._builder.setTopDrawable(drawable, int(background_color))
        return self

    def set_top_animation(self, res_id, size, auto_repeat, background_color, layer_colors: Optional[Dict[str, int]] = None):
        if layer_colors:
            HashMap = jclass("java.util.HashMap")
            m = HashMap()
            for k, v in layer_colors.items():
                m.put(k, jclass("java.lang.Integer")(int(v)))
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color), m)
        else:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color))
        return self

    def set_dim_enabled(self, enabled):
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key):
        self._builder.setDialogButtonColorKey(int(theme_key))
        return self

    def set_blurred_background(self, blur, blur_behind_if_possible=True):
        try:
            self._builder.setBlurredBackground(bool(blur), bool(blur_behind_if_possible))
        except Exception:
            pass
        return self

    def set_cancelable(self, cancelable):
        self._cancelable = bool(cancelable)
        return self

    def set_canceled_on_touch_outside(self, cancel):
        self._cancel_outside = bool(cancel)
        return self

    def create(self):
        self._dialog = self._builder.create()
        if hasattr(self, "_cancelable"):
            self._dialog.setCancelable(self._cancelable)
        if hasattr(self, "_cancel_outside"):
            self._dialog.setCanceledOnTouchOutside(self._cancel_outside)
        return self

    def show(self):
        from android_utils import run_on_ui_thread

        def go():
            if self._dialog is None:
                self.create()
            self._dialog.show()
            for b in getattr(self, "_pending_red", []):
                try:
                    tv = self._dialog.getButton(b)
                    if tv is not None:
                        Theme = jclass("org.telegram.ui.ActionBar.Theme")
                        tv.setTextColor(Theme.getColor(Theme.key_text_RedBold))
                except Exception:
                    pass
        run_on_ui_thread(go)
        return self

    def dismiss(self):
        from android_utils import run_on_ui_thread
        if self._dialog is not None:
            run_on_ui_thread(lambda: self._dialog.dismiss())

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type):
        return self._dialog.getButton(int(button_type)) if self._dialog is not None else None

    def set_progress(self, progress):
        if self._dialog is not None:
            from android_utils import run_on_ui_thread
            run_on_ui_thread(lambda: self._dialog.setProgress(int(progress)))
