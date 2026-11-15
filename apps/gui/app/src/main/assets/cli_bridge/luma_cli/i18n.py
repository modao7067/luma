import os

def _normalize(value):
    if not value or not isinstance(value, str):
        return None
    v = value.strip().lower()
    if not v:
        return None
    return "zh" if v.startswith("zh") else "en"

_current_lang = None
_current_lang_env = None

def set_lang(value):
    global _current_lang, _current_lang_env
    _current_lang = _normalize(value)
    _current_lang_env = os.environ.get("LUMA_LANG")

def is_zh():
    raw_env = os.environ.get("LUMA_LANG")
    if _current_lang is not None and raw_env == _current_lang_env:
        return _current_lang == "zh"
    env_override = _normalize(raw_env)
    if env_override is not None:
        return env_override == "zh"
    return True

def lang_code():
    return "zh" if is_zh() else "en"

def tr(zh, en):
    return zh if is_zh() else en
