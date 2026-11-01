"""exteraGram-compatible settings rows. The engine renders them in
PluginSettingsActivity (Java) through plain maps."""
from typing import Any, Callable, List, Optional


class _Item:
    type = ""

    def to_dict(self):
        d = dict(self.__dict__)
        d["type"] = self.type
        return d


class Header(_Item):
    type = "header"

    def __init__(self, text: str):
        self.text = text


class Divider(_Item):
    type = "divider"

    def __init__(self, text: Optional[str] = None):
        self.text = text


class Switch(_Item):
    type = "switch"

    def __init__(self, key: str, text: str, default: bool, subtext: Optional[str] = None, icon: Optional[str] = None,
                 on_change: Optional[Callable[[bool], None]] = None, on_long_click: Optional[Callable] = None,
                 link_alias: Optional[str] = None):
        self.key = key
        self.text = text
        self.default = bool(default)
        self.subtext = subtext
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias


class Selector(_Item):
    type = "selector"

    def __init__(self, key: str, text: str, default: int, items: Optional[List[str]] = None, icon: Optional[str] = None,
                 on_change: Optional[Callable[[int], None]] = None, on_long_click: Optional[Callable] = None,
                 link_alias: Optional[str] = None, subtext: Optional[str] = None):
        self.key = key
        self.text = text
        self.default = int(default)
        self.items = list(items or [])
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias
        self.subtext = subtext


class Input(_Item):
    type = "input"

    def __init__(self, key: str, text: str, default: str = "", subtext: Optional[str] = None, icon: Optional[str] = None,
                 on_change: Optional[Callable[[str], None]] = None, on_long_click: Optional[Callable] = None,
                 link_alias: Optional[str] = None):
        self.key = key
        self.text = text
        self.default = default if default is not None else ""
        self.subtext = subtext
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias


class Text(_Item):
    type = "text"

    def __init__(self, text: str, subtext: Optional[str] = None, icon: Optional[str] = None, accent: bool = False,
                 red: bool = False, on_click: Optional[Callable] = None, on_long_click: Optional[Callable] = None,
                 create_sub_fragment: Optional[Callable[[], List[Any]]] = None, link_alias: Optional[str] = None):
        self.text = text
        self.subtext = subtext
        self.icon = icon
        self.accent = accent
        self.red = red
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.create_sub_fragment = create_sub_fragment
        self.link_alias = link_alias


class EditText(_Item):
    type = "edittext"

    def __init__(self, key: str, default: str = "", hint: str = "", multiline: bool = False, max_length: int = 0,
                 mask: Optional[str] = None, on_change: Optional[Callable[[str], None]] = None):
        self.key = key
        self.default = default if default is not None else ""
        self.hint = hint
        self.multiline = multiline
        self.max_length = max_length
        self.mask = mask
        self.on_change = on_change


class Custom(_Item):
    type = "custom"

    def __init__(self, item=None, view=None, factory=None, factory_args=None, on_click: Optional[Callable] = None,
                 on_long_click: Optional[Callable] = None, create_sub_fragment: Optional[Callable] = None,
                 link_alias: Optional[str] = None):
        self.item = item
        self.view = view
        self.factory = factory
        self.factory_args = factory_args
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.create_sub_fragment = create_sub_fragment
        self.link_alias = link_alias


# ShuzaGram extras -------------------------------------------------------------

class Slider(_Item):
    type = "slider"

    def __init__(self, key: str, text: str, default: int, min: int = 0, max: int = 100, step: int = 1,
                 on_change: Optional[Callable[[int], None]] = None, format: Optional[Callable[[int], str]] = None):
        self.key = key
        self.text = text
        self.default = int(default)
        self.min = int(min)
        self.max = int(max)
        self.step = int(step)
        self.on_change = on_change
        self.format = format


class Button(_Item):
    type = "text"

    def __init__(self, text: str, on_click: Callable, icon: Optional[str] = None, red: bool = False):
        self.text = text
        self.subtext = None
        self.icon = icon
        self.accent = not red
        self.red = red
        self.on_click = on_click
        self.on_long_click = None
        self.create_sub_fragment = None
        self.link_alias = None


class SimpleSettingFactory:
    """Minimal stand-in: builds a view per row via `create(context)`."""

    def __init__(self, create, bind=None):
        self.create = create
        self.bind = bind
