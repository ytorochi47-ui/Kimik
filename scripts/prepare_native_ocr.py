"""Fetch pinned PP-OCRv5 Mobile models, dictionary and CPU-only native libraries for CI.

Everything is verified against SHA-256 so a changed or tampered upstream file fails
the build instead of silently entering the APK. Nothing here needs an API key.
Upstream code/models: nihui/ncnn-android-ppocrv5 (BSD-3-Clause, Tencent THL A29 Limited).
"""
import hashlib
import pathlib
import shutil
import time
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CPP = ROOT / 'app/src/main/cpp'
ASSETS = ROOT / 'app/src/main/assets'
THIRD = CPP / 'third_party'
VERSION = '6e6240de82ca10083846b0268c4c4438e13a276e'
BASE = f'https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/{VERSION}/app/src/main'
ABIS = ('arm64-v8a', 'armeabi-v7a')

FILES = [
    (f'{BASE}/jni/ppocrv5_dict.h', CPP / 'ppocrv5_dict.h',
     'bb7397a57300fccbf86cf527ad199b12a0a2e3ccec8afc636af2f9ee695bbd11'),
    (f'{BASE}/assets/PP_OCRv5_mobile_det.ncnn.param', ASSETS / 'PP_OCRv5_mobile_det.ncnn.param',
     '358f459680ae0e7a73e477469e529ce116f68c629019ec7a0b6457d2d9117934'),
    (f'{BASE}/assets/PP_OCRv5_mobile_det.ncnn.bin', ASSETS / 'PP_OCRv5_mobile_det.ncnn.bin',
     '857a96bc963725105b78a178dfcc3c0c3db1a7b9eef32244367b2cb105ccf60b'),
    (f'{BASE}/assets/PP_OCRv5_mobile_rec.ncnn.param', ASSETS / 'PP_OCRv5_mobile_rec.ncnn.param',
     'f52a6586ac3338d8c350db0c9f3c55ff2ecd3763327f9bc7f0efc091f8a63e74'),
    (f'{BASE}/assets/PP_OCRv5_mobile_rec.ncnn.bin', ASSETS / 'PP_OCRv5_mobile_rec.ncnn.bin',
     '49d9907a55ba20fa6637f9f788f66ab00793bc8ce57a733a09dbe86b9a2e3db0'),
]
ARCHIVES = [
    ('https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android.zip', 'ncnn',
     '62a9edb493ceb42a5c9cbf2d3a79b3f44bff06c625134ffb64d183dcc42c25fc'),
    ('https://github.com/nihui/opencv-mobile/releases/download/v37/opencv-mobile-4.14.0-android.zip', 'opencv',
     '96f120018c0acfb492721a3c45ec9ec84235a307c75600da3e70d4bfc1f0a3f5'),
]
OTHER_ABIS = ('x86', 'x86_64', 'riscv64')


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def download(url: str, dest: pathlib.Path, digest: str) -> None:
    if dest.is_file() and sha256(dest) == digest:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(4):
        try:
            print('Downloading', url, flush=True)
            req = urllib.request.Request(url, headers={'User-Agent': 'WebtoonLite-CI'})
            with urllib.request.urlopen(req, timeout=180) as response, dest.open('wb') as out:
                shutil.copyfileobj(response, out)
            got = sha256(dest)
            if got != digest:
                raise RuntimeError(f'SHA-256 mismatch for {url}: expected {digest}, got {got}')
            return
        except Exception:
            dest.unlink(missing_ok=True)
            if attempt == 3:
                raise
            time.sleep(3 + attempt * 5)


for url, dest, digest in FILES:
    download(url, dest, digest)

for url, name, digest in ARCHIVES:
    target = THIRD / name
    if target.is_dir():
        continue
    archive = THIRD / (name + '.zip')
    download(url, archive, digest)
    temp = THIRD / ('_unpacked_' + name)
    if temp.exists():
        shutil.rmtree(temp)
    temp.mkdir(parents=True)
    with zipfile.ZipFile(archive) as z:
        for member in z.namelist():
            parts = member.split('/')
            if any(p in OTHER_ABIS for p in parts):
                continue  # only arm ABIs are built; skip x86/riscv to save CI time
            z.extract(member, temp)
    dirs = [p for p in temp.iterdir() if p.is_dir()]
    if len(dirs) != 1:
        raise RuntimeError(f'Unrecognized {name} archive layout: {dirs}')
    shutil.move(str(dirs[0]), str(target))
    shutil.rmtree(temp)
    archive.unlink()

for abi in ABIS:
    if not (THIRD / f'ncnn/{abi}/lib/cmake/ncnn/ncnnConfig.cmake').exists():
        raise RuntimeError(f'ncnn CMake package not found for {abi}')
    if not (THIRD / f'opencv/sdk/native/staticlibs/{abi}/libopencv_core.a').exists():
        raise RuntimeError(f'OpenCV static libraries not found for {abi}')
if not (THIRD / 'opencv/sdk/native/jni/OpenCVConfig.cmake').exists():
    raise RuntimeError('OpenCV CMake package not found')
print('PP-OCRv5 Mobile native dependencies are ready.')
