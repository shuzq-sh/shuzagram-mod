"""exteraGram-compatible reflection helpers."""
from java import jclass

_Class = jclass("java.lang.Class")
_Modifier = jclass("java.lang.reflect.Modifier")


def _loader():
    try:
        return jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getClassLoader()
    except Exception:
        return None


def find_class(class_name: str):
    """Return the Java class (as a Chaquopy class proxy) or None."""
    try:
        return jclass(class_name)
    except Exception:
        pass
    try:
        return jclass(_Class.forName(class_name, False, _loader()).getName())
    except Exception:
        return None


def _java_class(obj_or_class):
    try:
        if isinstance(obj_or_class, type):
            return obj_or_class.getClass() if False else _Class.forName(obj_or_class.__javaclass__.replace("/", "."), False, _loader())
    except Exception:
        pass
    try:
        if obj_or_class.getClass().getName() == "java.lang.Class":
            return obj_or_class
    except Exception:
        pass
    try:
        return obj_or_class.getClass()
    except Exception:
        return None


def _find_field(clazz, name):
    c = clazz
    while c is not None:
        try:
            f = c.getDeclaredField(name)
            f.setAccessible(True)
            return f
        except Exception:
            c = c.getSuperclass()
    return None


def _find_method(clazz, name, arg_count=None, arg_types=None):
    c = clazz
    while c is not None:
        for m in c.getDeclaredMethods():
            if m.getName() != name:
                continue
            if arg_types is not None:
                pt = [t.getName() for t in m.getParameterTypes()]
                if pt != list(arg_types):
                    continue
            elif arg_count is not None and len(m.getParameterTypes()) != arg_count:
                continue
            m.setAccessible(True)
            return m
        c = c.getSuperclass()
    return None


def get_private_field(obj, field_name: str):
    try:
        f = _find_field(obj.getClass(), field_name)
        return f.get(obj) if f is not None else None
    except Exception:
        return None


def set_private_field(obj, field_name: str, new_value):
    try:
        f = _find_field(obj.getClass(), field_name)
        if f is None:
            return False
        f.set(obj, new_value)
        return True
    except Exception:
        return False


def get_static_private_field(clazz, field_name: str):
    try:
        f = _find_field(_java_class(clazz), field_name)
        return f.get(None) if f is not None else None
    except Exception:
        return None


def set_static_private_field(clazz, field_name: str, new_value):
    try:
        f = _find_field(_java_class(clazz), field_name)
        if f is None:
            return False
        f.set(None, new_value)
        return True
    except Exception:
        return False


def get_private_method(clazz, method_name: str, *param_types):
    """Return an accessible java.lang.reflect.Method (searches superclasses)."""
    jc = _java_class(clazz)
    if jc is None:
        return None
    if param_types:
        names = []
        for t in param_types:
            if isinstance(t, str):
                names.append(t)
            else:
                names.append(_java_class(t).getName())
        return _find_method(jc, method_name, arg_types=names)
    return _find_method(jc, method_name)


def call_private_method(obj, method_name: str, *args):
    """Call an instance method by name, matching on argument count."""
    m = _find_method(obj.getClass(), method_name, arg_count=len(args))
    if m is None:
        raise AttributeError(method_name)
    return m.invoke(obj, *args)


def call_static_private_method(clazz, method_name: str, *args):
    m = _find_method(_java_class(clazz), method_name, arg_count=len(args))
    if m is None:
        raise AttributeError(method_name)
    return m.invoke(None, *args)


def new_instance(clazz, *args):
    jc = _java_class(clazz)
    for c in jc.getDeclaredConstructors():
        if len(c.getParameterTypes()) == len(args):
            c.setAccessible(True)
            try:
                return c.newInstance(*args)
            except Exception:
                continue
    raise TypeError("no constructor with %d args" % len(args))
