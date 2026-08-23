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

def get_proot_deb_url(arch):
    # Mapping
    apt_arch = arch
    if arch == 'arm64':
        apt_arch = 'aarch64'
    
    pkg_url = f"https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-{apt_arch}/Packages.gz"
    req = urllib.request.Request(pkg_url, headers={'User-Agent': 'Mozilla/5.0 (Android; Termux-Build)'})
    try:
        content = gzip.decompress(urllib.request.urlopen(req, timeout=30).read()).decode('utf-8', errors='ignore')
        for block in content.split('\n\n'):
            if 'Package: proot\n' in block:
                for line in block.split('\n'):
                    if line.startswith('Filename: '):
                        return 'https://packages-cf.termux.dev/apt/termux-main/' + line.split(': ')[1].strip()
    except Exception as e:
        log(f"Warning: Failed to fetch Packages.gz: {e}")
    
    # Fallback to direct latest known path if Packages.gz fails
    return f"https://packages-cf.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_{apt_arch}.deb"

def patch_zip(zip_path, arch):
    if not os.path.exists(zip_path) or os.path.getsize(zip_path) == 0:
        log(f"Zip file not found or empty: {zip_path}")
        return

    if not zipfile.is_zipfile(zip_path):
        log(f"File is not a valid zip: {zip_path}, removing it...")
        os.remove(zip_path)
        return

    # Check if proot is already present
    with zipfile.ZipFile(zip_path, 'r') as zf:
        namelist = zf.namelist()
        if 'bin/proot' in namelist:
            log(f"proot is already present in {os.path.basename(zip_path)}")
            return

    log(f"Injecting proot into {os.path.basename(zip_path)} for {arch}...")
    deb_url = get_proot_deb_url(arch)
    log(f"Downloading {deb_url}...")
    
    req = urllib.request.Request(deb_url, headers={'User-Agent': 'Mozilla/5.0 (Android; Termux-Build)'})
    deb_data = urllib.request.urlopen(req, timeout=60).read()

    with tempfile.TemporaryDirectory() as tmpdir:
        deb_file = os.path.join(tmpdir, 'proot.deb')
        with open(deb_file, 'wb') as f:
            f.write(deb_data)

        # Extract ar deb
        subprocess.run(['ar', 'x', deb_file], cwd=tmpdir, check=True)
        data_tar = os.path.join(tmpdir, 'data.tar.xz')
        if not os.path.exists(data_tar):
            # check data.tar.gz or other formats
            for f in os.listdir(tmpdir):
                if f.startswith('data.tar'):
                    data_tar = os.path.join(tmpdir, f)
                    break

        subprocess.run(['tar', '-xf', data_tar], cwd=tmpdir, check=True)

        extracted_usr = os.path.join(tmpdir, 'data/data/com.termux/files/usr')
        if not os.path.exists(extracted_usr):
            log(f"Error: Could not find extracted usr directory in deb")
            return

        # Open zip in append mode
        with zipfile.ZipFile(zip_path, 'a', compression=zipfile.ZIP_DEFLATED) as zf:
            existing_names = set(zf.namelist())
            for root, dirs, files in os.walk(extracted_usr):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, extracted_usr)
                    
                    # We only need bin/proot, libexec/proot/*, and man/doc if wanted
                    if rel_path.startswith('bin/proot') or rel_path.startswith('libexec/proot/'):
                        if rel_path not in existing_names:
                            log(f"Adding {rel_path} to {os.path.basename(zip_path)}")
                            # Set executable permissions (0755) in zip entry
                            zinfo = zipfile.ZipInfo(rel_path)
                            zinfo.external_attr = 0o755 << 16  # unix file permissions
                            with open(full_path, 'rb') as f:
                                zf.writestr(zinfo, f.read())

    log(f"Successfully injected proot into {os.path.basename(zip_path)}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: patch_bootstrap_with_proot.py <zip_path> <arch>")
        sys.exit(1)
    patch_zip(sys.argv[1], sys.argv[2])
