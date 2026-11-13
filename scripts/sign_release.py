"""Use this Mac's existing signing key; never expose a secret in args or logs."""
import ctypes as C
import os
import subprocess
from pathlib import Path

security = C.CDLL('/System/Library/Frameworks/Security.framework/Security')
pointer = C.c_void_p
security.SecKeychainFindGenericPassword.argtypes = [pointer, C.c_uint32, C.c_char_p, C.c_uint32, C.c_char_p, C.POINTER(C.c_uint32), C.POINTER(pointer), pointer]
security.SecKeychainFindGenericPassword.restype = C.c_int
security.SecKeychainItemFreeContent.argtypes = [pointer, pointer]
service = b'ai.hyundaehwa.release-signing'
account = b'hyundaehwa-release'
length = C.c_uint32()
data = pointer()
status = security.SecKeychainFindGenericPassword(None, len(service), service, len(account), account, C.byref(length), C.byref(data), None)
if status != 0:
    raise SystemExit('이 Mac의 출시 키체인 항목을 찾지 못했습니다. 키를 새로 만들지 말고 기존 키를 복구하세요.')
password = C.string_at(data, length.value).decode()
security.SecKeychainItemFreeContent(None, data)
keystore = Path.home() / '.local/share/hyundaehwa/signing/hyundaehwa-release.jks'
if not keystore.is_file():
    raise SystemExit('기존 출시 서명키가 없습니다. 같은 이름의 새 키를 만들지 마세요.')
project = Path(__file__).resolve().parents[1]
environment = os.environ | {'HYUNDAEHWA_KEYSTORE': str(keystore), 'HYUNDAEHWA_STOREPASS': password}
raise SystemExit(subprocess.run([os.environ.get('HYUNDAEHWA_GRADLE_BIN', str(project/'gradlew')), '--offline', '--no-daemon', ':core:test', ':app:assembleRelease', ':app:assembleReleaseQa', ':app:lintRelease'], cwd=project, env=environment).returncode)
