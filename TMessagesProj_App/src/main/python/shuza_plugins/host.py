"""ShuzaGram Mod plugin engine entry point. Called once from ApplicationLoaderImpl."""
from java import jclass

_Log = jclass("android.util.Log")


def init():
    _Log.i("ShuzaPlugins", "starting engine")
    from shuza_plugins import engine
    engine.start()
    _Log.i("ShuzaPlugins", engine.engine_info())
