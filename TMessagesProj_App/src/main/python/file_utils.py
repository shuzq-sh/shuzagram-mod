"""exteraGram-compatible file helpers."""
import os
import traceback

from java import jclass

_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")


def _ctx():
    return _ApplicationLoader.applicationContext


def get_plugins_dir():
    from shuza_plugins import engine
    return engine.plugins_dir()


def get_files_dir():
    return str(_ApplicationLoader.getFilesDirFixed().getAbsolutePath())


def get_cache_dir():
    try:
        FileLoader = jclass("org.telegram.messenger.FileLoader")
        return str(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).getAbsolutePath())
    except Exception:
        return str(_ctx().getCacheDir().getAbsolutePath())


def _media_dir(kind):
    try:
        FileLoader = jclass("org.telegram.messenger.FileLoader")
        return str(FileLoader.getDirectory(getattr(FileLoader, kind)).getAbsolutePath())
    except Exception:
        return get_cache_dir()


def get_images_dir():
    return _media_dir("MEDIA_DIR_IMAGE")


def get_videos_dir():
    return _media_dir("MEDIA_DIR_VIDEO")


def get_audios_dir():
    return _media_dir("MEDIA_DIR_AUDIO")


def get_documents_dir():
    return _media_dir("MEDIA_DIR_DOCUMENT")


def get_temp_dir():
    path = os.path.join(str(_ctx().getCacheDir().getAbsolutePath()), "plugins_tmp")
    ensure_dir_exists(path)
    return path


def ensure_dir_exists(path: str):
    os.makedirs(path, exist_ok=True)
    return path


def list_dir(path, recursive=False, include_files=True, include_dirs=False, extensions=None):
    out = []
    exts = None
    if extensions:
        exts = tuple(e if e.startswith(".") else "." + e for e in extensions)
    if not os.path.isdir(path):
        return out
    walker = os.walk(path) if recursive else [(path, [d for d in os.listdir(path) if os.path.isdir(os.path.join(path, d))],
                                              [f for f in os.listdir(path) if os.path.isfile(os.path.join(path, f))])]
    for root, dirs, files in walker:
        if include_dirs:
            out.extend(os.path.join(root, d) for d in dirs)
        if include_files:
            for f in files:
                if exts is None or f.lower().endswith(exts):
                    out.append(os.path.join(root, f))
    return out


def file_exists(path):
    return os.path.exists(path)


def write_file(path: str, content: str):
    ensure_dir_exists(os.path.dirname(path) or ".")
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return True


def read_file(path: str):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        traceback.print_exc()
        return None


def write_file_bytes(path: str, content: bytes):
    ensure_dir_exists(os.path.dirname(path) or ".")
    with open(path, "wb") as f:
        f.write(content)
    return True


def read_file_bytes(path: str):
    try:
        with open(path, "rb") as f:
            return f.read()
    except Exception:
        traceback.print_exc()
        return None


def delete_file(path: str):
    try:
        if os.path.exists(path):
            os.remove(path)
            return True
    except Exception:
        traceback.print_exc()
    return False


class FilesController:
    """Custom handling for file extensions tapped in chats."""
    SUPPORT_ICONS = False

    class Place:
        UNKNOWN = "UNKNOWN"
        ChatActivity = "ChatActivity"
        FilteredSearchView = "FilteredSearchView"
        SharedMediaLayout = "SharedMediaLayout"
        SearchDownloadsContainer = "SearchDownloadsContainer"
        ChannelAdminLogActivity = "ChannelAdminLogActivity"

    class FileInfo:
        def __init__(self, ext, on_click, whitelist_places=None, blacklist_places=None, get_icon=None):
            self.ext = ext.lower().lstrip(".")
            self.on_click = on_click
            self.whitelist_places = whitelist_places
            self.blacklist_places = blacklist_places
            self.get_icon = get_icon

    class OnClickArgs:
        def __init__(self, place, file, file_name, message, activity, parent_fragment):
            self.place = place
            self.file = file
            self.file_name = file_name
            self.message = message
            self.activity = activity
            self.parent_fragment = parent_fragment

    class ExtensionAlreadyRegistered(Exception):
        pass

    class ExtensionNotRegistered(Exception):
        pass

    class SecretInvalid(Exception):
        pass

    _registry = {}

    @classmethod
    def register(cls, file_info):
        import secrets
        if file_info.ext in cls._registry:
            raise cls.ExtensionAlreadyRegistered(file_info.ext)
        secret = secrets.token_hex(8)
        cls._registry[file_info.ext] = (file_info, secret)
        return secret

    @classmethod
    def unregister(cls, ext, secret):
        ext = ext.lower().lstrip(".")
        entry = cls._registry.get(ext)
        if entry is None:
            raise cls.ExtensionNotRegistered(ext)
        if entry[1] != secret:
            raise cls.SecretInvalid(ext)
        del cls._registry[ext]

    @classmethod
    def handle(cls, place, file, file_name, message, activity, parent_fragment):
        ext = (file_name or "").rsplit(".", 1)[-1].lower() if file_name and "." in file_name else ""
        entry = cls._registry.get(ext)
        if entry is None:
            return False
        info = entry[0]
        if info.whitelist_places and place not in info.whitelist_places:
            return False
        if info.blacklist_places and place in info.blacklist_places:
            return False
        try:
            info.on_click(cls.OnClickArgs(place, file, file_name, message, activity, parent_fragment))
        except Exception:
            traceback.print_exc()
        return True
