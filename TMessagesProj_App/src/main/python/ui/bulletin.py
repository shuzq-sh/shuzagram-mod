"""exteraGram-compatible bottom notifications (Telegram 'bulletins')."""
from typing import Callable, Optional

from java import dynamic_proxy, jclass

_BulletinFactory = jclass("org.telegram.ui.Components.BulletinFactory")
_R = jclass("org.telegram.messenger.R")


def _run(fn):
    from android_utils import run_on_ui_thread
    run_on_ui_thread(fn)


def _factory(fragment=None):
    try:
        if fragment is None:
            LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
            fragment = LaunchActivity.getSafeLastFragment()
        if fragment is not None:
            return _BulletinFactory.of(fragment)
    except Exception:
        pass
    return _BulletinFactory.global_()


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @staticmethod
    def show_simple(text, icon_res_id, fragment=None):
        _run(lambda: _factory(fragment).createSimpleBulletin(int(icon_res_id), str(text)).show())

    @staticmethod
    def show_two_line(title, subtitle, icon_res_id, fragment=None):
        _run(lambda: _factory(fragment).createSimpleBulletin(int(icon_res_id), str(title), str(subtitle)).show())

    @staticmethod
    def show_info(message, fragment=None):
        BulletinHelper.show_simple(message, _R.raw.info, fragment)

    @staticmethod
    def show_error(message, fragment=None):
        BulletinHelper.show_simple(message, _R.raw.error, fragment)

    @staticmethod
    def show_success(message, fragment=None):
        BulletinHelper.show_simple(message, _R.raw.contact_check, fragment)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click: Optional[Callable[[], None]], fragment=None,
                         duration=DURATION_PROLONG):
        from android_utils import R

        def go():
            b = _factory(fragment).createSimpleBulletin(int(icon_res_id), str(text), str(button_text), int(duration),
                                                        R(on_click if on_click else (lambda: None)))
            b.show()
        _run(go)

    @staticmethod
    def show_undo(text, on_undo: Callable[[], None], on_action: Optional[Callable[[], None]] = None, subtitle=None,
                  fragment=None):
        from android_utils import R

        def go():
            f = _factory(fragment)
            if subtitle:
                b = f.createUndoBulletin(str(text), str(subtitle), R(on_undo), R(on_action or (lambda: None)))
            else:
                b = f.createUndoBulletin(str(text), R(on_undo), R(on_action or (lambda: None)))
            b.show()
        _run(go)

    @staticmethod
    def show_copied_to_clipboard(message=None, fragment=None):
        if message:
            BulletinHelper.show_simple(message, _R.raw.copy, fragment)
        else:
            _run(lambda: _factory(fragment).createCopyBulletin(jclass("org.telegram.messenger.LocaleController").getString(_R.string.TextCopied)).show())

    @staticmethod
    def show_link_copied(is_private_link_info=False, fragment=None):
        _run(lambda: _factory(fragment).createCopyLinkBulletin(bool(is_private_link_info)).show())

    @staticmethod
    def show_file_saved_to_gallery(is_video=False, amount=1, fragment=None):
        FileType = _BulletinFactory.FileType
        t = FileType.VIDEOS if is_video and amount > 1 else FileType.VIDEO if is_video else FileType.PHOTOS if amount > 1 else FileType.PHOTO
        _run(lambda: _factory(fragment).createDownloadBulletin(t, int(amount), None).show())

    @staticmethod
    def show_file_saved_to_downloads(file_type_enum_name="UNKNOWN", amount=1, fragment=None):
        FileType = _BulletinFactory.FileType
        try:
            t = getattr(FileType, file_type_enum_name)
        except Exception:
            t = FileType.UNKNOWN
        _run(lambda: _factory(fragment).createDownloadBulletin(t, int(amount), None).show())
