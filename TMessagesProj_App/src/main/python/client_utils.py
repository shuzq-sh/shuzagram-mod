"""exteraGram-compatible client helpers: queues, requests, sending, controllers."""
import os
import traceback

from java import dynamic_proxy, jclass

_Utilities = jclass("org.telegram.messenger.Utilities")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_AccountInstance = jclass("org.telegram.messenger.AccountInstance")
_ConnectionsManager = jclass("org.telegram.tgnet.ConnectionsManager")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
_DispatchQueue = jclass("org.telegram.messenger.DispatchQueue")
_Log = jclass("android.util.Log")

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

_queues = {}


def get_queue_by_name(name):
    if name in _queues:
        return _queues[name]
    q = None
    try:
        q = getattr(_Utilities, name)
    except Exception:
        q = None
    if q is None:
        q = _DispatchQueue(name)
    _queues[name] = q
    return q


def run_on_queue(fn, queue=PLUGINS_QUEUE, delay_ms=0):
    from android_utils import R
    q = queue if not isinstance(queue, str) else get_queue_by_name(queue)
    r = R(fn)
    if delay_ms and delay_ms > 0:
        q.postRunnable(r, int(delay_ms))
    else:
        q.postRunnable(r)
    return r


def _account(account):
    return _UserConfig.selectedAccount if account is None else int(account)


class RequestCallback(dynamic_proxy(_RequestDelegate)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self, response, error):
        try:
            self.fn(response, error)
        except Exception:
            _Log.e("ShuzaPlugins", traceback.format_exc())


def send_request(request, fn=None, account=None):
    cb = RequestCallback(fn if fn else (lambda r, e: None))
    return _ConnectionsManager.getInstance(_account(account)).sendRequest(request, cb)


# --- controllers -----------------------------------------------------------------

def get_account_instance(account=None):
    return _AccountInstance.getInstance(_account(account))


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_contacts_controller(account=None):
    return get_account_instance(account).getContactsController()


def get_media_data_controller(account=None):
    return get_account_instance(account).getMediaDataController()


def get_connections_manager(account=None):
    return get_account_instance(account).getConnectionsManager()


def get_location_controller(account=None):
    return get_account_instance(account).getLocationController()


def get_notifications_controller(account=None):
    return get_account_instance(account).getNotificationsController()


def get_messages_storage(account=None):
    return get_account_instance(account).getMessagesStorage()


def get_send_messages_helper(account=None):
    return get_account_instance(account).getSendMessagesHelper()


def get_file_loader(account=None):
    return get_account_instance(account).getFileLoader()


def get_secret_chat_helper(account=None):
    return get_account_instance(account).getSecretChatHelper()


def get_download_controller(account=None):
    return get_account_instance(account).getDownloadController()


def get_notifications_settings(account=None):
    return get_account_instance(account).getNotificationsSettings()


def get_notification_center(account=None):
    return get_account_instance(account).getNotificationCenter()


def get_user_config(account=None):
    return get_account_instance(account).getUserConfig()


def get_media_controller():
    return jclass("org.telegram.messenger.MediaController").getInstance()


def get_last_fragment():
    try:
        LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
        return LaunchActivity.getLastFragment()
    except Exception:
        try:
            LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
            inst = LaunchActivity.instance
            if inst is not None:
                return inst.getActionBarLayout().getLastFragment()
        except Exception:
            pass
    return None


def get_current_account():
    return _UserConfig.selectedAccount


def get_user(user_id, account=None):
    return get_messages_controller(account).getUser(jclass("java.lang.Long")(int(user_id)))


def get_chat(chat_id, account=None):
    return get_messages_controller(account).getChat(jclass("java.lang.Long")(abs(int(chat_id))))


# --- sending -----------------------------------------------------------------------

_SendMessagesHelper = jclass("org.telegram.messenger.SendMessagesHelper")


def _apply_parse_mode(params_obj, text_attr, parse_mode):
    if not parse_mode:
        return
    text = getattr(params_obj, text_attr)
    if text is None:
        return
    from extera_utils.text_formatting import parse_text
    parsed = parse_text(str(text), parse_mode)
    setattr(params_obj, text_attr, parsed["message"])
    entities = jclass("java.util.ArrayList")()
    for e in parsed["entities"]:
        entities.add(e)
    params_obj.entities = entities


def _params_from_dict(params):
    SMP = _SendMessagesHelper.SendMessageParams
    peer = int(params.get("peer", 0))
    message = params.get("message")
    p = SMP.of(message if message is not None else "", peer)
    if message is None:
        p.message = None
    for key, value in params.items():
        if key in ("peer", "message"):
            continue
        try:
            setattr(p, key, value)
        except Exception:
            _Log.w("ShuzaPlugins", "send_message: unknown param %s" % key)
    return p


def send_message(params, parse_mode=None, account=None):
    if isinstance(params, dict):
        p = _params_from_dict(params)
    else:
        p = params
    if parse_mode:
        if p.message:
            _apply_parse_mode(p, "message", parse_mode)
        elif p.caption:
            _apply_parse_mode(p, "caption", parse_mode)
    from android_utils import run_on_ui_thread
    run_on_ui_thread(lambda: get_send_messages_helper(account).sendMessage(p))
    return p


def send_text(peer_id, text, replyToMsg=None, parse_mode=None, account=None, **extra):
    params = {"peer": peer_id, "message": text}
    if replyToMsg is not None:
        params["replyToMsg"] = replyToMsg
        try:
            params["replyToTopMsg"] = replyToMsg
        except Exception:
            pass
    params.update(extra)
    return send_message(params, parse_mode=parse_mode, account=account)


def _send_media(peer_id, path, caption, kind, parse_mode=None, high_quality=False, account=None):
    from android_utils import run_on_ui_thread
    SendingMediaInfo = _SendMessagesHelper.SendingMediaInfo
    ArrayList = jclass("java.util.ArrayList")
    acc = get_account_instance(account)
    entities = None
    if caption and parse_mode:
        from extera_utils.text_formatting import parse_text
        parsed = parse_text(str(caption), parse_mode, is_caption=True)
        caption = parsed["caption"]
        entities = ArrayList()
        for e in parsed["entities"]:
            entities.add(e)

    def go():
        args_empty = jclass("org.telegram.messenger.SendMessageChatArguments").EMPTY
        if kind in ("photo", "video"):
            info = SendingMediaInfo()
            info.path = path
            info.caption = caption
            info.isVideo = kind == "video"
            if entities is not None:
                info.entities = entities
            media = ArrayList()
            media.add(info)
            # 22-argument overload: (account, media, dialogId, replyToMsg, replyToTopMsg, storyItem, quote,
            # forceDocument, groupMedia, editing, notify, scheduleDate, repeatPeriod, mode, updateStickersOrder,
            # inputContent, chatArguments, effectId, invertMedia, payStars, monoForumPeerId, suggestion)
            _SendMessagesHelper.prepareSendingMedia(acc, media, int(peer_id), None, None, None, None, False, False, None,
                                                    True, 0, 0, 0, False, None, args_empty, 0, False, 0, 0, None)
        else:
            paths = ArrayList()
            paths.add(path)
            originals = ArrayList()
            originals.add(path)
            # 23-argument overload with caption entities.
            _SendMessagesHelper.prepareSendingDocuments(acc, paths, originals, None, caption, entities, None, int(peer_id),
                                                        None, None, None, None, None, True, 0, 0, None, args_empty, 0, False, 0, 0, None)
    run_on_ui_thread(go)


def send_photo(peer_id, photo_path, caption=None, high_quality=False, parse_mode=None, account=None):
    return _send_media(peer_id, photo_path, caption, "photo", parse_mode, high_quality, account)


def send_video(peer_id, video_path, caption=None, parse_mode=None, account=None):
    return _send_media(peer_id, video_path, caption, "video", parse_mode, False, account)


def send_document(peer_id, file_path, caption=None, parse_mode=None, account=None):
    return _send_media(peer_id, file_path, caption, "document", parse_mode, False, account)


def send_audio(peer_id, audio_path, caption=None, parse_mode=None, account=None):
    return _send_media(peer_id, audio_path, caption, "document", parse_mode, False, account)


def edit_message(message_obj, text=None, file_path=None, with_spoiler=False, parse_mode=None, account=None):
    if parse_mode and str(parse_mode).upper() not in ("HTML", "MARKDOWN"):
        raise ValueError("parse_mode must be HTML or Markdown")
    from android_utils import run_on_ui_thread
    acc_id = message_obj.currentAccount if account is None else account
    entities = None
    if text is not None and parse_mode:
        from extera_utils.text_formatting import parse_text
        parsed = parse_text(str(text), parse_mode)
        text = parsed["message"]
        entities = jclass("java.util.ArrayList")()
        for e in parsed["entities"]:
            entities.add(e)

    def go():
        TLRPC = jclass("org.telegram.tgnet.TLRPC")
        req = TLRPC.TL_messages_editMessage()
        req.peer = get_messages_controller(acc_id).getInputPeer(message_obj.getDialogId())
        req.id = message_obj.getId()
        if text is not None:
            req.message = text
            req.flags |= 2048
            req.no_webpage = False
            if entities is not None:
                req.entities = entities
                req.flags |= 8
        if file_path:
            # Upload through the regular editing path for media replacement.
            get_send_messages_helper(acc_id).editMessage(message_obj, None, None, None, None, None, False, with_spoiler, None)
        send_request(req, lambda r, e: r is not None and get_messages_controller(acc_id).processUpdates(r, False), acc_id)
    run_on_ui_thread(go)


# --- NotificationCenter ------------------------------------------------------------

_NC = jclass("org.telegram.messenger.NotificationCenter")


class NotificationCenterDelegate(dynamic_proxy(_NC.NotificationCenterDelegate)):
    def didReceivedNotification(self, id, account, args):
        pass


def add_observer(delegate, notification_id, account=None, global_center=False):
    center = _NC.getGlobalInstance() if global_center else get_notification_center(account)
    center.addObserver(delegate, int(notification_id))


def remove_observer(delegate, notification_id, account=None, global_center=False):
    center = _NC.getGlobalInstance() if global_center else get_notification_center(account)
    center.removeObserver(delegate, int(notification_id))
