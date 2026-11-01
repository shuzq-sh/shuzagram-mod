"""ShuzaGram plugin engine: loads .plugin files, wires hooks and serves the Java host."""
import os
import shutil
import sys
import traceback

from java import dynamic_proxy, jclass

import android_utils
import base_plugin
import hook_utils
from shuza_plugins import loader

TAG = "ShuzaPlugins"

_Log = jclass("android.util.Log")
_PluginHost = jclass("org.telegram.messenger.plugins.PluginHost")
_PyHook = jclass("org.telegram.messenger.plugins.PyHook")
_XposedBridge = jclass("de.robv.android.xposed.XposedBridge")
_ArrayList = jclass("java.util.ArrayList")
_HashMap = jclass("java.util.HashMap")
_Integer = jclass("java.lang.Integer")
_Boolean = jclass("java.lang.Boolean")


class Record:
    def __init__(self, meta, path):
        self.meta = meta
        self.module = None
        self.instance = None
        self.enabled = False
        self.error = ""
        self.path = path


_plugins = {}  # id -> Record
_setting_callbacks = {}  # token -> python callable
_menu_registry = {}  # item id -> (plugin id, MenuItemData)
_enabling = set()


# --- logging -------------------------------------------------------------------

def _log(msg):
    try:
        _Log.i(TAG, str(msg))
    except Exception:
        pass


def _log_error(msg, plugin=None):
    text = "%s\n%s" % (msg, traceback.format_exc())
    try:
        _Log.e(TAG, text)
    except Exception:
        pass
    if plugin is not None:
        try:
            logger = getattr(plugin, "logger", None)
            if logger is not None:
                logger.error(text)
        except Exception:
            pass


# --- persistence ---------------------------------------------------------------

def _prefs():
    return base_plugin.PluginsController.getInstance().preferences


def _enabled_key(plugin_id):
    return "plugin_enabled_%s" % plugin_id


def _read_enabled(plugin_id):
    try:
        return bool(_prefs().getBoolean(_enabled_key(plugin_id), False))
    except Exception:
        return False


def _write_enabled(plugin_id, value):
    try:
        _prefs().edit().putBoolean(_enabled_key(plugin_id), bool(value)).apply()
    except Exception:
        _log_error("failed to persist enabled flag for %s" % plugin_id)


# --- functions required by base_plugin -------------------------------------------

def plugins_dir():
    loader_cls = jclass("org.telegram.messenger.ApplicationLoader")
    path = os.path.join(str(loader_cls.getFilesDirFixed().getAbsolutePath()), "plugins")
    os.makedirs(path, exist_ok=True)
    return path


def as_class(cls):
    try:
        if isinstance(cls, str):
            found = hook_utils.find_class(cls)
            if found is None:
                return None
            return hook_utils._java_class(found)
        try:
            if cls.getClass().getName() == "java.lang.Class":
                return cls
        except Exception:
            pass
        result = hook_utils._java_class(cls)
        if result is not None:
            return result
        return cls.getClass()
    except Exception:
        _log_error("as_class failed for %r" % (cls,))
        return None


def hooks_changed():
    try:
        send = False
        specs = False
        for rec in list(_plugins.values()):
            inst = rec.instance
            if not rec.enabled or inst is None:
                continue
            if inst._send_hook_priority is not None:
                send = True
            if inst._hook_specs:
                specs = True
        _PluginHost.hasSendHooks = send
        _PluginHost.hasRequestHooks = specs
        _PluginHost.hasResponseHooks = specs
        _PluginHost.hasUpdateHooks = specs
    except Exception:
        _log_error("hooks_changed failed")


def _hook_filters_of(fn):
    try:
        return list(getattr(fn, "__hook_filters__", None) or [])
    except Exception:
        return []


def _passes(filters, param):
    for f in filters:
        try:
            if not f.check(param):
                return False
        except Exception:
            return False
    return True


class _HandlerProxy(dynamic_proxy(_PyHook.Handler)):
    def __init__(self, plugin, handler, before_filters, after_filters):
        super().__init__()
        self.plugin = plugin
        self.handler = handler
        self.before_filters = list(before_filters or [])
        self.after_filters = list(after_filters or [])

    def before(self, param):
        try:
            fn = getattr(self.handler, "before_hooked_method", None)
            if fn is None:
                return
            if not _passes(self.before_filters + _hook_filters_of(fn), param):
                return
            fn(param)
        except Exception:
            _log_error("before hook failed", self.plugin)

    def after(self, param):
        try:
            fn = getattr(self.handler, "after_hooked_method", None)
            if fn is None:
                return
            if not _passes(self.after_filters + _hook_filters_of(fn), param):
                return
            fn(param)
        except Exception:
            _log_error("after hook failed", self.plugin)


class _ReplacerProxy(dynamic_proxy(_PyHook.Replacer)):
    def __init__(self, plugin, handler, before_filters):
        super().__init__()
        self.plugin = plugin
        self.handler = handler
        self.before_filters = list(before_filters or [])

    def replace(self, param):
        try:
            fn = self.handler.replace_hooked_method
            if not _passes(self.before_filters + _hook_filters_of(fn), param):
                return None
            return fn(param)
        except Exception:
            _log_error("replacement hook failed", self.plugin)
            return None


def hook_member(plugin, method, handler, priority, before_filters, after_filters):
    try:
        if not _PyHook.ensureHooking():
            _log("method hooking unavailable on this device/arch")
            return None
        priority = int(priority if priority is not None else 50)
        if isinstance(handler, base_plugin.MethodReplacement):
            replacement = _PyHook.Replacement(_ReplacerProxy(plugin, handler, before_filters), priority)
            return _XposedBridge.hookMethod(method, replacement)
        proxy = _HandlerProxy(plugin, handler, before_filters, after_filters)
        return _XposedBridge.hookMethod(method, _PyHook(proxy, priority))
    except Exception:
        _log_error("hook_member failed for %r" % (method,), plugin)
        return None


def reload_settings_ui(plugin_id):
    try:
        nc = jclass("org.telegram.messenger.NotificationCenter")
        const = getattr(nc, "pluginSettingsRegistered", None)
        if const is None:
            return
        nc.getGlobalInstance().postNotificationName(const, plugin_id)
    except Exception:
        _log_error("reload_settings_ui failed")


# --- lifecycle -------------------------------------------------------------------

def _load_record(path):
    try:
        meta = loader.parse_meta(path)
        rec = Record(meta, path)
    except Exception as e:
        _log_error("failed to parse %s" % path)
        meta = loader.PluginMeta()
        meta.id = os.path.splitext(os.path.basename(path))[0]
        meta.name = meta.id
        meta.path = path
        rec = Record(meta, path)
        rec.error = "parse error: %s" % e
    return rec


def scan_plugins():
    try:
        directory = plugins_dir()
        names = sorted(n for n in os.listdir(directory) if n.endswith(".plugin"))
    except Exception:
        _log_error("scan_plugins failed")
        return list(_plugins.values())
    seen = set()
    for name in names:
        path = os.path.join(directory, name)
        try:
            rec = _load_record(path)
            seen.add(rec.meta.id)
            existing = _plugins.get(rec.meta.id)
            if existing is None:
                _plugins[rec.meta.id] = rec
            else:
                existing.path = path
                if not existing.enabled:
                    existing.meta = rec.meta
                    existing.error = rec.error
        except Exception:
            _log_error("scan failed for %s" % path)
    for pid in list(_plugins.keys()):
        rec = _plugins[pid]
        if pid not in seen and not rec.enabled and not os.path.exists(rec.path or ""):
            _plugins.pop(pid, None)
    return list(_plugins.values())


def _sorted_for_start(records):
    by_id = {r.meta.id: r for r in records}
    ordered = []
    state = {}

    def visit(rec):
        if state.get(rec.meta.id):
            return
        state[rec.meta.id] = True
        for dep in rec.meta.dependencies:
            d = by_id.get(dep)
            if d is not None:
                visit(d)
        ordered.append(rec)

    for rec in sorted(records, key=lambda r: -r.meta.priority):
        visit(rec)
    return ordered


def enable(plugin_id):
    rec = _plugins.get(plugin_id)
    if rec is None:
        return "plugin not found: %s" % plugin_id
    if rec.enabled and rec.instance is not None:
        return None
    if plugin_id in _enabling:
        return "dependency cycle at %s" % plugin_id
    _enabling.add(plugin_id)
    try:
        for dep in rec.meta.dependencies:
            if dep not in _plugins:
                rec.error = "missing dependency: %s" % dep
                return rec.error
            err = enable(dep)
            if err:
                rec.error = "dependency %s failed: %s" % (dep, err)
                return rec.error
        module = None
        instance = None
        try:
            module = loader.load_module(rec.path, rec.meta)
            instance = loader.find_plugin_instance(module, rec.meta)
            rec.module = module
            rec.instance = instance
            instance.on_plugin_load()
        except BaseException as e:
            if isinstance(e, (KeyboardInterrupt, SystemExit)):
                raise
            _log_error("failed to enable %s" % plugin_id, instance)
            _cleanup_instance(rec)
            rec.error = "%s: %s" % (type(e).__name__, e)
            rec.enabled = False
            hooks_changed()
            return rec.error
        rec.error = ""
        rec.enabled = True
        _write_enabled(plugin_id, True)
        hooks_changed()
        _log("enabled plugin %s" % plugin_id)
        return None
    except Exception as e:
        _log_error("enable crashed for %s" % plugin_id)
        rec.error = str(e)
        return rec.error
    finally:
        _enabling.discard(plugin_id)


def _cleanup_instance(rec):
    inst = rec.instance
    if inst is not None:
        for unhook in list(getattr(inst, "_method_hooks", None) or []):
            try:
                unhook.unhook()
            except Exception:
                _log_error("unhook failed", inst)
        try:
            inst._method_hooks.clear()
        except Exception:
            pass
        try:
            inst._menu_items.clear()
        except Exception:
            pass
    prefix = "%s::" % rec.meta.id
    for token in [t for t in _setting_callbacks if t.startswith(prefix)]:
        _setting_callbacks.pop(token, None)
    sys.modules.pop("shuza_plugin_%s" % rec.meta.id, None)
    rec.module = None
    rec.instance = None


def disable(plugin_id):
    rec = _plugins.get(plugin_id)
    if rec is None:
        return "plugin not found: %s" % plugin_id
    err = None
    try:
        inst = rec.instance
        if inst is not None:
            try:
                inst.on_plugin_unload()
            except Exception as e:
                _log_error("on_plugin_unload failed for %s" % plugin_id, inst)
                err = None
        _cleanup_instance(rec)
        rec.enabled = False
        _write_enabled(plugin_id, False)
        hooks_changed()
    except Exception as e:
        _log_error("disable crashed for %s" % plugin_id)
        err = str(e)
    return err


_bridge = None


def start():
    global _bridge
    try:
        _bridge = EngineBridge()
        _PluginHost.register(_bridge)
    except Exception:
        _log_error("failed to register engine bridge")
    records = scan_plugins()
    wanted = [r for r in records if _read_enabled(r.meta.id)]
    enabled_count = 0
    for rec in _sorted_for_start(wanted):
        try:
            if enable(rec.meta.id) is None:
                enabled_count += 1
        except Exception:
            _log_error("start: enable failed for %s" % rec.meta.id)
    hooks_changed()
    _log("engine started: %d plugins, %d enabled" % (len(records), enabled_count))


def install(path, enable_flag):
    try:
        meta = loader.parse_meta(path)
        directory = plugins_dir()
        dest = os.path.join(directory, "%s.plugin" % meta.id)
        was_enabled = False
        old = _plugins.get(meta.id)
        if old is not None:
            was_enabled = old.enabled
            if old.enabled:
                disable(meta.id)
        if os.path.abspath(path) != os.path.abspath(dest):
            shutil.copyfile(path, dest)
        rec = _load_record(dest)
        _plugins[rec.meta.id] = rec
        if enable_flag or was_enabled:
            err = enable(rec.meta.id)
            if err:
                return "error: %s" % err
        return rec.meta.id
    except Exception as e:
        _log_error("install failed for %s" % path)
        return "error: %s" % e


def uninstall(plugin_id):
    try:
        rec = _plugins.get(plugin_id)
        if rec is None:
            return "error: plugin not found: %s" % plugin_id
        disable(plugin_id)
        try:
            if rec.path and os.path.exists(rec.path):
                os.remove(rec.path)
        except Exception as e:
            return "error: %s" % e
        _plugins.pop(plugin_id, None)
        try:
            _prefs().edit().remove(_enabled_key(plugin_id)).apply()
        except Exception:
            pass
        hooks_changed()
        return "ok"
    except Exception as e:
        _log_error("uninstall failed for %s" % plugin_id)
        return "error: %s" % e


def set_enabled(plugin_id, enabled):
    try:
        err = enable(plugin_id) if enabled else disable(plugin_id)
        return err if err else "ok"
    except Exception as e:
        _log_error("setEnabled failed for %s" % plugin_id)
        return "error: %s" % e


# --- helpers -----------------------------------------------------------------------

def _enabled_instances():
    return [r.instance for r in list(_plugins.values()) if r.enabled and r.instance is not None]


def _cancel():
    return _PluginHost.CANCEL


def _map_to_dict(jmap):
    out = {}
    if jmap is None:
        return out
    try:
        it = jmap.keySet().iterator()
        while it.hasNext():
            key = it.next()
            try:
                out[str(key)] = jmap.get(key)
            except Exception:
                out[str(key)] = None
    except Exception:
        _log_error("map conversion failed")
    return out


def _is_result(res):
    return isinstance(res, base_plugin.HookResult)


def _matching_specs(name):
    """Yield enabled instances with a matching hook spec, highest priority first, once each."""
    entries = []
    for inst in _enabled_instances():
        for spec in list(inst._hook_specs):
            hname, substr, prio = spec
            if (substr and hname in name) or hname == name:
                entries.append((prio, inst))
    entries.sort(key=lambda e: -e[0])
    seen = set()
    out = []
    for prio, inst in entries:
        if id(inst) in seen:
            continue
        seen.add(id(inst))
        out.append(inst)
    return out


def _run_hooks(instances, call, attr, value):
    """Run hook calls, applying results. Returns CANCEL, new value or None."""
    changed = False
    for inst in instances:
        try:
            res = call(inst, value)
        except Exception:
            _log_error("hook callback failed", inst)
            continue
        if not _is_result(res):
            continue
        strategy = res.strategy
        if strategy == base_plugin.HookStrategy.CANCEL:
            return _cancel()
        if strategy in (base_plugin.HookStrategy.MODIFY, base_plugin.HookStrategy.MODIFY_FINAL):
            new = getattr(res, attr, None)
            if new is not None:
                value = new
                changed = True
            if strategy == base_plugin.HookStrategy.MODIFY_FINAL:
                break
    return value if changed else None


# --- dispatch --------------------------------------------------------------------

def on_send_message(account, params):
    insts = [i for i in _enabled_instances() if i._send_hook_priority is not None]
    insts.sort(key=lambda i: -i._send_hook_priority)
    return _run_hooks(insts, lambda inst, v: inst.on_send_message_hook(account, v), "params", params)


def pre_request(account, name, request):
    return _run_hooks(_matching_specs(name),
                      lambda inst, v: inst.pre_request_hook(name, account, v), "request", request)


def post_request(account, name, response, error):
    return _run_hooks(_matching_specs(name),
                      lambda inst, v: inst.post_request_hook(name, account, v, error), "response", response)


def on_update(account, name, update):
    return _run_hooks(_matching_specs(name),
                      lambda inst, v: inst.on_update_hook(name, account, v), "update", update)


def on_updates(account, name, updates):
    return _run_hooks(_matching_specs(name),
                      lambda inst, v: inst.on_updates_hook(name, account, v), "updates", updates)


def on_app_event(event):
    for inst in _enabled_instances():
        try:
            inst.on_app_event(event)
        except Exception:
            _log_error("on_app_event failed", inst)


def menu_items(menu_type, context):
    _ = _map_to_dict(context)
    entries = []
    for rec in list(_plugins.values()):
        if not rec.enabled or rec.instance is None:
            continue
        for item in list(rec.instance._menu_items.values()):
            try:
                if str(item.menu_type) == str(menu_type):
                    entries.append(item)
            except Exception:
                pass
    entries.sort(key=lambda it: -int(it.priority or 0))
    out = _ArrayList()
    for item in entries:
        m = _HashMap()
        m.put("id", str(item.item_id or ""))
        m.put("text", str(item.text or ""))
        m.put("icon", str(item.icon or ""))
        m.put("subtext", str(item.subtext or ""))
        m.put("priority", _Integer.valueOf(int(item.priority or 0)))
        out.add(m)
    return out


def menu_click(item_id, context):
    ctx = _map_to_dict(context)
    item_id = str(item_id)
    target = None
    owner = None
    for rec in list(_plugins.values()):
        if not rec.enabled or rec.instance is None:
            continue
        item = rec.instance._menu_items.get(item_id)
        if item is not None:
            target = item
            owner = rec.instance
            break
    if target is None:
        return

    def run():
        try:
            target.on_click(ctx)
        except Exception:
            _log_error("menu click failed for %s" % item_id, owner)

    android_utils.run_on_ui_thread(run)


def list_plugins():
    out = _ArrayList()
    for rec in list(_plugins.values()):
        m = _HashMap()
        m.put("id", rec.meta.id)
        m.put("name", rec.meta.name)
        m.put("version", rec.meta.version)
        m.put("author", rec.meta.author)
        m.put("description", rec.meta.description)
        m.put("enabled", _Boolean.valueOf(bool(rec.enabled)))
        m.put("error", rec.error or "")
        out.add(m)
    return out


def _register_callback(plugin_id, ident, attr, fn):
    token = "%s::%s::%s" % (plugin_id, ident, attr)
    _setting_callbacks[token] = fn
    return token


def _to_java_value(value):
    if isinstance(value, bool):
        return _Boolean.valueOf(value)
    if isinstance(value, int):
        return _Integer.valueOf(value)
    if isinstance(value, str):
        return value
    if isinstance(value, float):
        return str(value)
    return None


def _item_to_map(plugin_id, index, item):
    d = item.to_dict()
    ident = d.get("key") or "item%d" % index
    m = _HashMap()
    primary = None
    for attr, value in d.items():
        if value is None or attr == "key" and not isinstance(value, str):
            continue
        if callable(value):
            name = "change" if attr == "on_change" else attr
            token = _register_callback(plugin_id, ident, name, value)
            m.put(attr + "_token", token)
            if attr == "on_change" or (primary is None and attr == "on_click"):
                primary = token
            continue
        if isinstance(value, (list, tuple)):
            lst = _ArrayList()
            for v in value:
                lst.add(str(v))
            m.put(attr, lst)
            continue
        jv = _to_java_value(value)
        if jv is not None:
            m.put(attr, jv)
    if primary is not None:
        m.put("_token", primary)
    return m


def create_settings(plugin_id):
    out = _ArrayList()
    rec = _plugins.get(plugin_id)
    if rec is None or rec.instance is None:
        return out
    prefix = "%s::" % plugin_id
    for token in [t for t in _setting_callbacks if t.startswith(prefix)]:
        _setting_callbacks.pop(token, None)
    try:
        items = rec.instance.create_settings() or []
    except Exception:
        _log_error("create_settings failed for %s" % plugin_id, rec.instance)
        return out
    for index, item in enumerate(items):
        try:
            out.add(_item_to_map(plugin_id, index, item))
        except Exception:
            _log_error("settings row conversion failed for %s" % plugin_id, rec.instance)
    return out


def invoke_setting_callback(token, *args):
    fn = _setting_callbacks.get(str(token))
    if fn is None:
        return None
    try:
        return fn(*args)
    except Exception:
        _log_error("setting callback %s failed" % token)
        return None


def invoke_menu(item_id, context=None):
    menu_click(item_id, context)


def all_settings_java(plugin_id):
    """Java bridge: plugin settings as a java HashMap."""
    out = _HashMap()
    try:
        for k, v in base_plugin.all_settings(plugin_id).items():
            jv = _to_java_value(v)
            out.put(k, jv if jv is not None else str(v))
    except Exception:
        _log_error("all_settings_java failed for %s" % plugin_id)
    return out


def set_setting_java(plugin_id, key, value):
    try:
        base_plugin.write_setting(plugin_id, key, value)
    except Exception:
        _log_error("set_setting_java failed for %s" % plugin_id)


def plugin_path(plugin_id):
    rec = _plugins.get(plugin_id)
    return rec.path if rec is not None else None


def plugin_log(plugin_id):
    rec = _plugins.get(plugin_id)
    try:
        if rec is not None and rec.instance is not None and rec.instance.logger is not None:
            return "\n".join(rec.instance.logger.lines)
    except Exception:
        pass
    return ""


def engine_info():
    try:
        hooking = bool(_PyHook.ensureHooking())
    except Exception:
        hooking = False
    return "plugins=%d enabled=%d hooking=%s" % (
        len(_plugins), sum(1 for r in _plugins.values() if r.enabled), hooking)


# --- Java bridge -------------------------------------------------------------------

def _safe(fn, *args):
    try:
        return fn(*args)
    except Exception:
        _log_error("bridge call %s failed" % getattr(fn, "__name__", fn))
        return None


class EngineBridge(dynamic_proxy(_PluginHost.Engine)):
    def onAppEvent(self, event):
        _safe(on_app_event, event)

    def onSendMessage(self, account, params):
        return _safe(on_send_message, account, params)

    def preRequest(self, account, name, request):
        return _safe(pre_request, account, name, request)

    def postRequest(self, account, name, response, error):
        return _safe(post_request, account, name, response, error)

    def onUpdate(self, account, name, update):
        return _safe(on_update, account, name, update)

    def onUpdates(self, account, name, updates):
        return _safe(on_updates, account, name, updates)

    def menuItems(self, menu_type, context):
        res = _safe(menu_items, menu_type, context)
        return res if res is not None else _ArrayList()

    def menuClick(self, item_id, context):
        _safe(menu_click, item_id, context)

    def listPlugins(self):
        res = _safe(list_plugins)
        return res if res is not None else _ArrayList()

    def install(self, path, enable_flag):
        res = _safe(install, path, enable_flag)
        return res if res is not None else "error: install failed"

    def setEnabled(self, plugin_id, enabled):
        res = _safe(set_enabled, plugin_id, enabled)
        return res if res is not None else "error: failed"

    def uninstall(self, plugin_id):
        res = _safe(uninstall, plugin_id)
        return res if res is not None else "error: failed"

    def createSettings(self, plugin_id):
        res = _safe(create_settings, plugin_id)
        return res if res is not None else _ArrayList()

    def pluginLog(self, plugin_id):
        res = _safe(plugin_log, plugin_id)
        return res if res is not None else ""

    def engineInfo(self):
        res = _safe(engine_info)
        return res if res is not None else ""

    def invokeSetting(self, token, value):
        return _safe(invoke_setting_callback, token, value)
