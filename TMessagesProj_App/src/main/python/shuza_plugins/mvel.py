"""Tiny evaluator for the simple MVEL-like conditions plugins pass to
HookFilter.Condition / menu item `condition`. Supports a safe subset:
names, .field access, ==, !=, <, >, <=, >=, &&, ||, !, parentheses, numbers,
strings, true/false/null. Unknown expressions evaluate to False."""
import ast
import operator

_OPS = {
    ast.Eq: operator.eq, ast.NotEq: operator.ne, ast.Lt: operator.lt,
    ast.Gt: operator.gt, ast.LtE: operator.le, ast.GtE: operator.ge,
}


def _to_py(expr):
    # MVEL uses the same basic operators as Python for this subset.
    return expr.replace("&&", " and ").replace("||", " or ").replace("!=", "__NE__") \
               .replace("!", " not ").replace("__NE__", "!=").replace("null", "None") \
               .replace("true", "True").replace("false", "False")


def _eval(node, ctx):
    if isinstance(node, ast.Expression):
        return _eval(node.body, ctx)
    if isinstance(node, ast.BoolOp):
        vals = [_eval(v, ctx) for v in node.values]
        if isinstance(node.op, ast.And):
            return all(vals)
        return any(vals)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
        return not _eval(node.operand, ctx)
    if isinstance(node, ast.Compare):
        left = _eval(node.left, ctx)
        for op, comp in zip(node.ops, node.comparators):
            right = _eval(comp, ctx)
            fn = _OPS.get(type(op))
            if fn is None or not fn(left, right):
                return False
            left = right
        return True
    if isinstance(node, ast.Attribute):
        obj = _eval(node.value, ctx)
        return getattr(obj, node.attr, None)
    if isinstance(node, ast.Name):
        if node.id in ("True", "False", "None"):
            return {"True": True, "False": False, "None": None}[node.id]
        return ctx.get(node.id)
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Call):
        return None
    raise ValueError("unsupported")


def evaluate(expr, ctx):
    try:
        tree = ast.parse(_to_py(str(expr)), mode="eval")
        return bool(_eval(tree, ctx))
    except Exception:
        return False
