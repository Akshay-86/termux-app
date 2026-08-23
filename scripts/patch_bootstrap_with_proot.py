#!/usr/bin/env python3
import sys
import os
import zipfile
import tempfile
import urllib.request
import gzip
import tarfile
import subprocess

def log(msg):
    print(f"[inject_proot] {msg}", flush=True)

def get_deb_url(arch, pkg_name):
    apt_arch = arch
    if arch == 'arm64':
        apt_arch = 'aarch64'
    
    pkg_url = f"https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-{apt_arch}/Packages.gz"
    req = urllib.request.Request(pkg_url, headers={'User-Agent': 'Mozilla/5.0 (Android; Termux-Build)'})
    try:
        content = gzip.decompress(urllib.request.urlopen(req, timeout=30).read()).decode('utf-8', errors='ignore')
        for block in content.split('\n\n'):
            if f'Package: {pkg_name}\n' in block:
                for line in block.split('\n'):
                    if line.startswith('Filename: '):
                        return 'https://packages-cf.termux.dev/apt/termux-main/' + line.split(': ')[1].strip()
    except Exception as e:
        log(f"Warning: Failed to fetch Packages.gz for {pkg_name}: {e}")
    
    # Fallbacks
    fallbacks = {
        'proot': f"https://packages-cf.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_{apt_arch}.deb",
        'libtalloc': f"https://packages-cf.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_{apt_arch}.deb",
        'libandroid-shmem': f"https://packages-cf.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_{apt_arch}.deb"
    }
    return fallbacks.get(pkg_name)

def extract_and_inject_deb(deb_url, zip_path, allowed_prefixes):
    req = urllib.request.Request(deb_url, headers={'User-Agent': 'Mozilla/5.0 (Android; Termux-Build)'})
    deb_data = urllib.request.urlopen(req, timeout=60).read()

    with tempfile.TemporaryDirectory() as tmpdir:
        deb_file = os.path.join(tmpdir, 'pkg.deb')
        with open(deb_file, 'wb') as f:
            f.write(deb_data)

        # Extract ar deb
        subprocess.run(['ar', 'x', deb_file], cwd=tmpdir, check=True)
        data_tar = os.path.join(tmpdir, 'data.tar.xz')
        if not os.path.exists(data_tar):
            for f in os.listdir(tmpdir):
                if f.startswith('data.tar'):
                    data_tar = os.path.join(tmpdir, f)
                    break

        subprocess.run(['tar', '-xf', data_tar], cwd=tmpdir, check=True)

        extracted_usr = os.path.join(tmpdir, 'data/data/com.termux/files/usr')
        if not os.path.exists(extracted_usr):
            log(f"Warning: Could not find extracted usr directory in {os.path.basename(deb_url)}")
            return

        with zipfile.ZipFile(zip_path, 'a', compression=zipfile.ZIP_DEFLATED) as zf:
            existing_names = set(zf.namelist())
            for root, dirs, files in os.walk(extracted_usr):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, extracted_usr)
                    
                    # Check allowed prefixes
                    matches = any(rel_path.startswith(prefix) for prefix in allowed_prefixes)
                    if matches:
                        if rel_path not in existing_names:
                            log(f"Adding {rel_path} to {os.path.basename(zip_path)}")
                            zinfo = zipfile.ZipInfo(rel_path)
                            zinfo.external_attr = 0o755 << 16
                            with open(full_path, 'rb') as f:
                                zf.writestr(zinfo, f.read())
                            existing_names.add(rel_path)

def patch_zip(zip_path, arch):
    if not os.path.exists(zip_path) or os.path.getsize(zip_path) == 0:
        log(f"Zip file not found or empty: {zip_path}")
        return

    if not zipfile.is_zipfile(zip_path):
        log(f"File is not a valid zip: {zip_path}, removing it...")
        os.remove(zip_path)
        return

    # Check if proot AND its shared libraries are already present
    with zipfile.ZipFile(zip_path, 'r') as zf:
        namelist = set(zf.namelist())
        has_proot = 'bin/proot' in namelist
        has_talloc = any(name.startswith('lib/libtalloc.so') for name in namelist)
        if has_proot and has_talloc:
            log(f"proot and libraries already present in {os.path.basename(zip_path)}")
            return

    log(f"Injecting proot and dependencies into {os.path.basename(zip_path)} for {arch}...")
    
    # 1. PRoot
    proot_url = get_deb_url(arch, 'proot')
    log(f"Downloading {proot_url}...")
    extract_and_inject_deb(proot_url, zip_path, ['bin/proot', 'libexec/proot/'])
    
    # 2. libtalloc
    talloc_url = get_deb_url(arch, 'libtalloc')
    log(f"Downloading {talloc_url}...")
    extract_and_inject_deb(talloc_url, zip_path, ['lib/libtalloc.so'])
    
    # 3. libandroid-shmem
    shmem_url = get_deb_url(arch, 'libandroid-shmem')
    log(f"Downloading {shmem_url}...")
    extract_and_inject_deb(shmem_url, zip_path, ['lib/libandroid-shmem.so'])

    log(f"Successfully injected proot & dependencies into {os.path.basename(zip_path)}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: patch_bootstrap_with_proot.py <zip_path> <arch>")
        sys.exit(1)
    patch_zip(sys.argv[1], sys.argv[2])
