"""Host/device path discovery shared by the scripts under tools/.

Nothing in the repository should hard-code where the JDK, the Android SDK, aapt2 or the
on-device development checkout live. Every value below can be overridden with an
environment variable; the fallbacks reproduce the locations the scripts used before
(so an existing device-side setup keeps working) and then fall back to whatever is
installed on the current machine.

    ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK (else local.properties sdk.dir)
    JAVA_HOME                         JDK 21
    AAPT2, APKSIGNER                  explicit tool overrides
    LUMA_DEVICE_ROOT                  on-device work directory (default /data/local/tmp/eta)
    LUMA_DEVICE_REPO                  on-device checkout of this repository
"""
from pathlib import Path
import os
import shutil

REPO = Path(__file__).resolve().parents[1]


def _sdk_from_local_properties():
    props = REPO / 'local.properties'
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return line.split('=', 1)[1].strip()
    return None


def _newest(paths):
    paths = sorted(p for p in paths if p.exists())
    return paths[-1] if paths else None


ANDROID_HOME = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
                    or _sdk_from_local_properties() or '/opt/android-sdk')


def _build_tools_file(relative, preferred='35.0.0'):
    wanted = ANDROID_HOME / 'build-tools' / preferred / relative
    if wanted.exists():
        return str(wanted)
    newest = _newest((ANDROID_HOME / 'build-tools').glob('*/' + relative))
    return str(newest or wanted)


def platform_jar(api=35):
    wanted = ANDROID_HOME / 'platforms' / f'android-{api}' / 'android.jar'
    if wanted.exists():
        return str(wanted)
    newest = _newest((ANDROID_HOME / 'platforms').glob('android-*/android.jar'))
    return str(newest or wanted)


ANDROID_JAR = platform_jar(35)
D8_JAR = _build_tools_file('lib/d8.jar')
D8 = _build_tools_file('d8')
APKSIGNER = os.environ.get('APKSIGNER') or _build_tools_file('apksigner')

_legacy_java_home = Path('/usr/lib/jvm/java-21-openjdk-arm64')
if os.environ.get('JAVA_HOME'):
    JAVA_HOME = os.environ['JAVA_HOME']
elif _legacy_java_home.is_dir():
    JAVA_HOME = str(_legacy_java_home)
else:
    _java = shutil.which('java')
    JAVA_HOME = str(Path(_java).resolve().parents[1]) if _java else str(_legacy_java_home)
JAVA = str(Path(JAVA_HOME) / 'bin' / 'java')

_legacy_aapt2 = Path('/opt/eta-aapt2/aapt2')
AAPT2 = os.environ.get('AAPT2') or (str(_legacy_aapt2) if _legacy_aapt2.exists()
                                    else _build_tools_file('aapt2', '37.0.0'))

DEVICE_ROOT = os.environ.get('LUMA_DEVICE_ROOT', '/data/local/tmp/eta')
DEVICE_REPO = os.environ.get('LUMA_DEVICE_REPO', DEVICE_ROOT + '/luma')
