"""exteraGram-compatible plugin base for ShuzaGram Mod.

Plugins are single-file Python modules (.plugin) with a class that inherits
BasePlugin. The engine (shuza_plugins.engine) loads them, wires their hooks
into Telegram and keeps their settings.
"""
import json
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from java import jclass

_PluginsController = None


def _controller():
    global _PluginsController
    if _PluginsController is None:
        _PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")
    return _PluginsController


class _ControllerProxy:
    """`from base_plugin import PluginsController` returns the Java class."""

    def __getattr__(self, name):
        return getattr(_controller(), name)


PluginsController = _ControllerProxy()


class HookStrategy:
    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"


class HookResult:
    def __init__(self, strategy=HookStrategy.DEFAULT, request=None, response=None,
                 update=None, updates=None, params=None):
        self.strategy = strategy
        self.request = request
        self.response = response
        self.update = update
        self.updates = updates
        self.params = params

    def __repr__(self):
        return "HookResult(%s)" % self.strategy


class AppEvent:
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"


class MenuItemType:
    MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU"
    DRAWER_MENU = "DRAWER_MENU"
    MAIN_MENU = "MAIN_MENU"
    CHAT_ACTION_MENU = "CHAT_ACTION_MENU"
    PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU"
    # ShuzaGram extras
    SETTINGS_MENU = "SETTINGS_MENU"
    DIALOG_CONTEXT_MENU = "DIALOG_CONTEXT_MENU"


@dataclass
class MenuItemData:
    menu_type: str
    text: str
    on_click: Callable[[Dict[str, Any]], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


# --- method hooking ------------------------------------------------------------

class MethodHook:
    """Override before_hooked_method / after_hooked_method."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement:
    """Override replace_hooked_method; its return value replaces the call."""

    def replace_hooked_method(self, param):
        return None


class BaseHook(MethodHook):
    def __init__(self, before=None, after=None):
        self._before = before
        self._after = after

    def before_hooked_method(self, param):
        if self._before:
            self._before(param)

    def after_hooked_method(self, param):
        if self._after:
            self._after(param)


class _Filter:
    def __init__(self, fn, desc):
        self.fn = fn
        self.desc = desc

    def check(self, param):
        try:
            return bool(self.fn(param))
        except Exception:
            return False

    def __repr__(self):
        return "HookFilter(%s)" % self.desc


def _result(param):
    try:
        return param.getResult()
    except Exception:
        return None


def _arg(param, index):
    try:
        return param.args[index]
    except Exception:
        return None


def _is_instance(obj, clazz):
    if obj is None:
        return False
    try:
        return clazz.isInstance(obj)
    except Exception:
        try:
            return isinstance(obj, clazz)
        except Exception:
            return False


class HookFilter:
    RESULT_IS_NULL = _Filter(lambda p: _result(p) is None, "RESULT_IS_NULL")
    RESULT_NOT_NULL = _Filter(lambda p: _result(p) is not None, "RESULT_NOT_NULL")
    RESULT_IS_TRUE = _Filter(lambda p: _result(p) is True, "RESULT_IS_TRUE")
    RESULT_IS_FALSE = _Filter(lambda p: _result(p) is False, "RESULT_IS_FALSE")

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return _Filter(lambda p: _is_instance(_result(p), clazz), "ResultIsInstanceOf")

    @staticmethod
    def ResultEqual(value):
        return _Filter(lambda p: _result(p) == value, "ResultEqual")

    @staticmethod
    def ResultNotEqual(value):
        return _Filter(lambda p: _result(p) != value, "ResultNotEqual")

    @staticmethod
    def ArgumentIsNull(index):
        return _Filter(lambda p: _arg(p, index) is None, "ArgumentIsNull")

    @staticmethod
    def ArgumentNotNull(index):
        return _Filter(lambda p: _arg(p, index) is not None, "ArgumentNotNull")

    @staticmethod
    def ArgumentIsTrue(index):
        return _Filter(lambda p: _arg(p, index) is True, "ArgumentIsTrue")

    @staticmethod
    def ArgumentIsFalse(index):
        return _Filter(lambda p: _arg(p, index) is False, "ArgumentIsFalse")

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        return _Filter(lambda p: _is_instance(_arg(p, index), clazz), "ArgumentIsInstanceOf")

    @staticmethod
    def ArgumentEqual(index, value):
        return _Filter(lambda p: _arg(p, index) == value, "ArgumentEqual")

    @staticmethod
    def ArgumentNotEqual(index, value):
        return _Filter(lambda p: _arg(p, index) != value, "ArgumentNotEqual")

    @staticmethod
    def Condition(condition, object=None):
        from shuza_plugins.mvel import evaluate

        def check(p):
            ctx = {"param": p, "args": p.args, "thisObject": p.thisObject, "result": _result(p), "object": object}
            return evaluate(condition, ctx)
        return _Filter(check, "Condition(%s)" % condition)

    @staticmethod
    def Or(*filters):
        return _Filter(lambda p: any(f.check(p) for f in filters), "Or")

    @staticmethod
    def And(*filters):
        return _Filter(lambda p: all(f.check(p) for f in filters), "And")

    @staticmethod
    def Not(f):
        return _Filter(lambda p: not f.check(p), "Not")


def hook_filters(*filters):
    """Decorator for before_hooked_method / after_hooked_method."""

    def deco(fn):
        fn.__hook_filters__ = list(filters)
        return fn
    return deco


# --- settings storage ----------------------------------------------------------

_JSON_PREFIX = "\u0000json:"


def _prefs():
    return _controller().getInstance().preferences


def _setting_key(plugin_id, key):
    return "plugin_setting_%s_%s" % (plugin_id, key)


def _from_java(value):
    if value is None:
        return None
    if isinstance(value, (bool, int, float, str)):
        if isinstance(value, str) and value.startswith(_JSON_PREFIX):
            try:
                return json.loads(value[len(_JSON_PREFIX):])
            except Exception:
                return value
        return value
    try:
        s = value.toString()
        if s.startswith(_JSON_PREFIX):
            return json.loads(s[len(_JSON_PREFIX):])
        return s
    except Exception:
        return value


def read_setting(plugin_id, key, default=None):
    try:
        allv = _prefs().getAll()
        full = _setting_key(plugin_id, key)
        if not allv.containsKey(full):
            return default
        value = _from_java(allv.get(full))
        # Keep the caller's type when the stored primitive differs (e.g. int vs float).
        if isinstance(default, bool) and not isinstance(value, bool):
            if isinstance(value, str):
                return value.lower() in ("true", "1", "yes")
            return bool(value)
        if isinstance(default, int) and not isinstance(default, bool) and isinstance(value, float):
            return int(value)
        if isinstance(default, int) and not isinstance(default, bool) and isinstance(value, str):
            try:
                return int(value)
            except ValueError:
                return default
        return value
    except Exception:
        return default


def write_setting(plugin_id, key, value):
    editor = _prefs().edit()
    full = _setting_key(plugin_id, key)
    if value is None:
        editor.remove(full)
    elif isinstance(value, bool):
        editor.putBoolean(full, value)
    elif isinstance(value, int):
        if -2 ** 31 <= value < 2 ** 31:
            editor.putInt(full, value)
        else:
            editor.putLong(full, value)
    elif isinstance(value, float):
        editor.putFloat(full, value)
    elif isinstance(value, str):
        editor.putString(full, value)
    else:
        try:
            editor.putString(full, _JSON_PREFIX + json.dumps(value, ensure_ascii=False))
        except TypeError:
            editor.putString(full, str(value))
    editor.apply()


def all_settings(plugin_id):
    prefix = "plugin_setting_%s_" % plugin_id
    out = {}
    try:
        allv = _prefs().getAll()
        it = allv.keySet().iterator()
        while it.hasNext():
            k = it.next()
            if k.startswith(prefix):
                out[k[len(prefix):]] = _from_java(allv.get(k))
    except Exception:
        pass
    return out


# --- logging -------------------------------------------------------------------

class PluginLogger:
    def __init__(self, plugin_id):
        self.tag = "plugin." + plugin_id
        self._log = jclass("android.util.Log")
        self.lines = []

    def _keep(self, level, msg):
        self.lines.append("%s %s" % (level, msg))
        if len(self.lines) > 400:
            del self.lines[:100]

    def debug(self, msg, *args):
        msg = str(msg) % args if args else str(msg)
        self._keep("D", msg)
        self._log.d(self.tag, msg)

    def info(self, msg, *args):
        msg = str(msg) % args if args else str(msg)
        self._keep("I", msg)
        self._log.i(self.tag, msg)

    def warning(self, msg, *args):
        msg = str(msg) % args if args else str(msg)
        self._keep("W", msg)
        self._log.w(self.tag, msg)

    warn = warning

    def error(self, msg, *args):
        msg = str(msg) % args if args else str(msg)
        self._keep("E", msg)
        self._log.e(self.tag, msg)

    def exception(self, msg="", *args):
        msg = (str(msg) % args if args else str(msg)) + "\n" + traceback.format_exc()
        self._keep("E", msg)
        self._log.e(self.tag, msg)

    def dump(self, obj):
        try:
            text = repr(obj)
            if hasattr(obj, "getClass"):
                cls = obj.getClass()
                fields = []
                c = cls
                while c is not None and c.getName() != "java.lang.Object":
                    for f in c.getDeclaredFields():
                        try:
                            f.setAccessible(True)
                            fields.append("%s=%r" % (f.getName(), f.get(obj)))
                        except Exception:
                            pass
                    c = c.getSuperclass()
                text = "%s {%s}" % (cls.getName(), ", ".join(fields))
        except Exception:
            text = repr(obj)
        self.debug(text)


# --- the plugin base class -----------------------------------------------------

class BasePlugin:
    id: str = ""
    name: str = ""

    def __init__(self):
        self.id = getattr(self, "id", "") or ""
        self._hook_specs = []          # (name, match_substring, priority)
        self._send_hook_priority = None
        self._method_hooks = []        # unhook objects
        self._menu_items = {}          # item_id -> MenuItemData
        self.logger = None

    # Plugins often skip super().__init__(); create the engine's fields lazily.
    _LAZY = {"_hook_specs": list, "_send_hook_priority": lambda: None, "_method_hooks": list,
             "_menu_items": dict, "logger": lambda: None}

    def __getattr__(self, name):
        factory = BasePlugin._LAZY.get(name)
        if factory is None:
            raise AttributeError(name)
        value = factory()
        object.__setattr__(self, name, value)
        return value

    # lifecycle
    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type):
        pass

    def create_settings(self) -> List[Any]:
        return []

    # hooks (override)
    def pre_request_hook(self, request_name, account, request) -> HookResult:
        return HookResult()

    def post_request_hook(self, request_name, account, response, error) -> HookResult:
        return HookResult()

    def on_update_hook(self, update_name, account, update) -> HookResult:
        return HookResult()

    def on_updates_hook(self, container_name, account, updates) -> HookResult:
        return HookResult()

    def on_send_message_hook(self, account, params) -> HookResult:
        return HookResult()

    # hook registration
    def add_hook(self, name, match_substring=False, priority=0):
        self._hook_specs.append((name, bool(match_substring), int(priority)))
        _engine().hooks_changed()

    def remove_hook(self, name):
        self._hook_specs = [h for h in self._hook_specs if h[0] != name]
        _engine().hooks_changed()

    def add_on_send_message_hook(self, priority=0):
        self._send_hook_priority = int(priority)
        _engine().hooks_changed()

    def remove_on_send_message_hook(self):
        self._send_hook_priority = None
        _engine().hooks_changed()

    # method hooking (Xposed API through Pine)
    def hook_method(self, method, handler=None, priority=50, before=None, after=None,
                    before_filters=None, after_filters=None):
        if handler is None:
            handler = BaseHook(before=before, after=after)
        unhook = _engine().hook_member(self, method, handler, priority, before_filters, after_filters)
        if unhook is not None:
            self._method_hooks.append(unhook)
        return unhook

    def hook_all_methods(self, cls, name, handler, priority=50):
        out = []
        clazz = _engine().as_class(cls)
        if clazz is None:
            return out
        c = clazz
        seen = set()
        while c is not None:
            for m in c.getDeclaredMethods():
                if m.getName() == name:
                    sig = m.toString()
                    if sig in seen:
                        continue
                    seen.add(sig)
                    u = self.hook_method(m, handler, priority)
                    if u is not None:
                        out.append(u)
            if out:
                break
            c = c.getSuperclass()
        return out

    def hook_all_constructors(self, cls, handler, priority=50):
        out = []
        clazz = _engine().as_class(cls)
        if clazz is None:
            return out
        for c in clazz.getDeclaredConstructors():
            u = self.hook_method(c, handler, priority)
            if u is not None:
                out.append(u)
        return out

    def unhook_method(self, unhook_obj):
        if unhook_obj is None:
            return
        try:
            unhook_obj.unhook()
        except Exception:
            pass
        try:
            self._method_hooks.remove(unhook_obj)
        except ValueError:
            pass

    # menus
    def add_menu_item(self, item: MenuItemData):
        item_id = item.item_id or "%s_%d" % (self.id, len(self._menu_items) + 1)
        item.item_id = item_id
        self._menu_items[item_id] = item
        return item_id

    def remove_menu_item(self, item_id):
        self._menu_items.pop(item_id, None)

    # settings
    def get_setting(self, key, default=None):
        return read_setting(self.id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        write_setting(self.id, key, value)
        if reload_settings:
            _engine().reload_settings_ui(self.id)

    def export_settings(self):
        return all_settings(self.id)

    def import_settings(self, settings, reload_settings=True):
        for k, v in (settings or {}).items():
            write_setting(self.id, k, v)
        if reload_settings:
            _engine().reload_settings_ui(self.id)

    # helpers
    def log(self, message):
        if self.logger:
            self.logger.info(message)

    def client(self, account=None):
        import client_utils
        return client_utils.get_account_instance(account)

    @property
    def plugin_dir(self):
        import file_utils
        return file_utils.get_plugins_dir()


def _engine():
    from shuza_plugins import engine
    return engine
