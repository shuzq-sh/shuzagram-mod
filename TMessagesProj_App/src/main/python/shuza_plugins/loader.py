"""Plugin file parsing and module loading for the ShuzaGram plugin engine."""
import ast
import os
import re
import sys
import types

import base_plugin

_META_NAMES = (
    "__id__", "__name__", "__description__", "__author__", "__version__", "__icon__",
    "__min_version__", "__app_version__", "__sdk_version__", "__dependencies__",
    "__requirements__", "__priority__", "__beta__", "__type__", "__all__",
)

_SPEC_SPLIT = re.compile(r"[<>=!~\s;\[(]")


class PluginMeta:
    def __init__(self):
        self.id = ""
        self.name = ""
        self.description = ""
        self.author = ""
        self.version = ""
        self.icon = ""
        self.min_version = ""
        self.app_version = ""
        self.sdk_version = ""
        self.dependencies = []
        self.requirements = []
        self.priority = 0
        self.beta = False
        self.type = ""
        self.path = ""
        self.raw = {}

    def __repr__(self):
        return "PluginMeta(id=%r, name=%r, version=%r)" % (self.id, self.name, self.version)


def _eval_node(node):
    """Evaluate a literal expression node; raise if it is not constant."""
    try:
        return ast.literal_eval(node)
    except Exception:
        pass
    expr = ast.Expression(body=node)
    ast.fix_missing_locations(expr)
    code = compile(expr, "<plugin-meta>", "eval")
    return eval(code, {"__builtins__": {}}, {})


def _as_str(value):
    if value is None:
        return ""
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8")
        except Exception:
            return ""
    return str(value)


def _as_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [p.strip() for p in value.split(",") if p.strip()]
    if isinstance(value, (list, tuple, set)):
        return [str(v).strip() for v in value if str(v).strip()]
    return []


def _clean_requirements(value):
    out = []
    for item in _as_list(value):
        name = _SPEC_SPLIT.split(item.strip(), 1)[0].strip()
        if name:
            out.append(name)
    return out


def _as_int(value, default=0):
    try:
        return int(value)
    except Exception:
        return default


def parse_meta(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    if text.startswith("\ufeff"):
        text = text[1:]

    values = {}
    tree = ast.parse(text, filename=path)
    for node in tree.body:
        targets = []
        value_node = None
        if isinstance(node, ast.Assign):
            targets = node.targets
            value_node = node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            targets = [node.target]
            value_node = node.value
        else:
            continue
        for target in targets:
            if isinstance(target, ast.Name) and target.id in _META_NAMES:
                try:
                    values[target.id] = _eval_node(value_node)
                except Exception:
                    pass

    meta = PluginMeta()
    meta.path = path
    meta.raw = values
    base = os.path.splitext(os.path.basename(path))[0]
    meta.id = _as_str(values.get("__id__")).strip() or base
    meta.name = _as_str(values.get("__name__")).strip() or meta.id
    meta.description = _as_str(values.get("__description__"))
    meta.author = _as_str(values.get("__author__"))
    meta.version = _as_str(values.get("__version__"))
    meta.icon = _as_str(values.get("__icon__"))
    meta.min_version = _as_str(values.get("__min_version__"))
    meta.app_version = _as_str(values.get("__app_version__"))
    meta.sdk_version = _as_str(values.get("__sdk_version__"))
    meta.dependencies = _as_list(values.get("__dependencies__"))
    meta.requirements = _clean_requirements(values.get("__requirements__"))
    meta.priority = _as_int(values.get("__priority__"), 0)
    beta = values.get("__beta__", False)
    meta.beta = beta.strip().lower() in ("1", "true", "yes") if isinstance(beta, str) else bool(beta)
    meta.type = _as_str(values.get("__type__"))
    return meta


def load_module(path, meta):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        source = f.read()
    if source.startswith("\ufeff"):
        source = source[1:]

    mod_name = "shuza_plugin_%s" % meta.id
    module = types.ModuleType(mod_name)
    module.__file__ = path
    module.__id__ = meta.id
    module.__name__ = mod_name
    for key, value in meta.raw.items():
        if key != "__name__":
            module.__dict__[key] = value
    module.__dict__.setdefault("__id__", meta.id)
    module.__dict__["__meta_name__"] = meta.name

    sys.modules[mod_name] = module
    try:
        code = compile(source, path, "exec")
        exec(code, module.__dict__)
    except BaseException:
        sys.modules.pop(mod_name, None)
        raise
    return module


def find_plugin_instance(module, meta=None):
    base = base_plugin.BasePlugin
    candidates = []
    for value in list(module.__dict__.values()):
        if isinstance(value, type) and value is not base:
            try:
                if issubclass(value, base):
                    candidates.append(value)
            except Exception:
                pass
    if not candidates:
        raise RuntimeError("no BasePlugin subclass found in plugin")

    module_name = module.__dict__.get("__name__", module.__name__)
    own = [c for c in candidates if c.__module__ == module_name or c.__module__ == module.__name__]
    chosen = (own or candidates)[-1] if len(own or candidates) > 1 else (own or candidates)[0]
    if len(own) > 1:
        # prefer a class that is not a base of another candidate (the most derived one)
        leaves = [c for c in own if not any(o is not c and issubclass(o, c) for o in own)]
        if leaves:
            chosen = leaves[-1]

    instance = chosen()

    plugin_id = meta.id if meta is not None else module.__dict__.get("__id__", "")
    plugin_name = meta.name if meta is not None else module.__dict__.get("__meta_name__", plugin_id)
    instance.id = plugin_id
    instance.name = plugin_name
    if getattr(instance, "logger", None) is None:
        instance.logger = base_plugin.PluginLogger(plugin_id)
    return instance
