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


def _gradle_property(key):
    gradle_home = Path(os.environ.get('GRADLE_USER_HOME') or (Path.home() / '.gradle'))
    for props in (REPO / 'gradle.properties', gradle_home / 'gradle.properties'):
        if props.is_file():
            for line in props.read_text().splitlines():
                stripped = line.strip()
                if stripped.startswith(key + '='):
                    value = stripped.split('=', 1)[1].strip()
                    if value:
                        return value
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

_default_arm64_java = Path('/usr/lib/jvm/java-21-openjdk-arm64')
if os.environ.get('JAVA_HOME'):
    JAVA_HOME = os.environ['JAVA_HOME']
elif _default_arm64_java.is_dir():
    JAVA_HOME = str(_default_arm64_java)
else:
    _java = shutil.which('java')
    JAVA_HOME = str(Path(_java).resolve().parents[1]) if _java else str(_default_arm64_java)
JAVA = str(Path(JAVA_HOME) / 'bin' / 'java')

_override_aapt2 = _gradle_property('android.aapt2FromMavenOverride')
AAPT2 = os.environ.get('AAPT2') or (_override_aapt2 if _override_aapt2 and Path(_override_aapt2).exists()
                                    else _build_tools_file('aapt2', '37.0.0'))


def _discover_device_root():
    explicit = os.environ.get('LUMA_DEVICE_ROOT')
    if explicit:
        return explicit
    mountinfo = Path('/proc/self/mountinfo')
    workspace = Path('/workspace')
    host_data = Path('/proc/1/root/data')
    if mountinfo.is_file() and workspace.is_dir() and host_data.is_dir():
        try:
            ws_ino = workspace.stat().st_ino
            for line in mountinfo.read_text().splitlines():
                fields = line.split()
                if len(fields) >= 5 and fields[4] == '/workspace' and fields[3].startswith('/'):
                    candidate = '/data' + fields[3]
                    probe = Path('/proc/1/root' + candidate)
                    if probe.is_dir() and probe.stat().st_ino == ws_ino:
                        return candidate
        except OSError:
            pass
    return '/data/local/tmp/luma'


DEVICE_ROOT = _discover_device_root()
if os.environ.get('LUMA_DEVICE_REPO'):
    DEVICE_REPO = os.environ['LUMA_DEVICE_REPO']
elif REPO.resolve().is_relative_to('/workspace'):
    DEVICE_REPO = DEVICE_ROOT + '/' + REPO.resolve().relative_to('/workspace').as_posix()
else:
    DEVICE_REPO = DEVICE_ROOT + '/luma'
