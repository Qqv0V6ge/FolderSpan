#!/usr/bin/env python3
"""Verify TURN TLS 443 with a rooted debug emulator and a strictly checked fixture CA."""

import argparse
from datetime import datetime, timedelta, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import socket
import ssl
import subprocess
import sys
import time

import verify_webrtc_interop as interop

PACKAGE = 'com.folderspan.core.test'
CONFIG = 'files/webrtc-interop-config.json'
FIXTURES = 'files/webrtc-tls-fixtures'


def certificates(directory):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    directory.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)

    def key():
        return rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def certificate(subject, issuer, public_key, issuer_key, start, end, extensions):
        builder = (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer)
                   .public_key(public_key).serial_number(x509.random_serial_number())
                   .not_valid_before(start).not_valid_after(end)
                   .add_extension(x509.SubjectKeyIdentifier.from_public_key(public_key), critical=False)
                   .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), critical=False))
        for extension, critical in extensions:
            builder = builder.add_extension(extension, critical)
        return builder.sign(issuer_key, hashes.SHA256())

    root_key = key()
    root_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'FolderSpan isolated TURN test CA')])
    root = certificate(root_name, root_name, root_key.public_key(), root_key,
                       now-timedelta(minutes=5), now+timedelta(days=1), [
                           (x509.BasicConstraints(ca=True, path_length=0), True),
                           (x509.KeyUsage(False, False, False, False, False, True, True, False, False), True)])
    (directory/'ca.der').write_bytes(root.public_bytes(serialization.Encoding.DER))
    (directory/'ca.pem').write_bytes(root.public_bytes(serialization.Encoding.PEM))
    for name in ('valid', 'wrong-host', 'expired', 'untrusted'):
        leaf_key = key()
        issuer_key = key() if name == 'untrusted' else root_key
        start, end = ((now-timedelta(days=2), now-timedelta(days=1)) if name == 'expired'
                      else (now-timedelta(minutes=5), now+timedelta(hours=12)))
        address = '127.0.0.2' if name == 'wrong-host' else '127.0.0.1'
        hostname = 'wrong-host.test' if name == 'wrong-host' else 'localhost'
        # M125 also applies X509_check_host; provide a DNS identity as well as the strict IP SAN.
        leaf = certificate(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hostname)]), root_name,
                           leaf_key.public_key(), issuer_key, start, end, [
                               (x509.BasicConstraints(ca=False, path_length=None), True),
                               (x509.KeyUsage(True, False, True, False, False, False, False, False, False), True),
                               (x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False),
                               (x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(address)),
                                                             x509.DNSName(hostname)]), False)])
        (directory/(name+'.der')).write_bytes(leaf.public_bytes(serialization.Encoding.DER))
        (directory/(name+'.pem')).write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
        path = directory/(name+'.key')
        path.write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
        path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--turnserver', required=True)
    parser.add_argument('--invalid-certificate', action='store_true')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This fixture only supports Android emulators')
    case = 'turn-tls-invalid' if args.invalid_certificate else 'turn-tls'
    output = interop.ROOT/'build/webrtc-ice-lab'/case
    output.mkdir(parents=True, exist_ok=True)
    if any((output/name).exists() for name in ('http.sock', 'tls.sock')):
        raise RuntimeError('An external TLS client is already active')
    for name in ('ready', 'client-complete.json', 'result.json', 'android-result.json'):
        (output/name).unlink(missing_ok=True)
    fixture = output/'fixtures'
    certificates(fixture)
    interop.OUTPUT = output
    adb = ['adb', '-s', args.serial]

    def android(command, **kwargs):
        return subprocess.run(adb+command, check=True, timeout=20, capture_output=True, **kwargs)

    def private_file(path, content):
        android(['shell', 'run-as', PACKAGE, 'sh', '-c', shlex.quote('umask 077; cat > '+path)], input=content)

    original_root = android(['shell', 'id', '-u'], text=True).stdout.strip() == '0'
    builds, reverses, chains = [], [], []
    installed = configured = rooted = False
    try:
        if not original_root:
            android(['root'])
            rooted = True
            android(['wait-for-device'])
        assert android(['shell', 'id', '-u'], text=True).stdout.strip() == '0', 'A rooted debug emulator is required'
        for path in (CONFIG, FIXTURES):
            exists = subprocess.run(adb+['shell', 'test', '-e', f'/data/user/0/{PACKAGE}/{path}'], timeout=10).returncode
            assert exists == 1, 'Existing private test configuration must not be overwritten'
        process = subprocess.run(adb+['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=10)
        assert not process.stdout.strip(), 'Test package is already running'
        current = android(['reverse', '--list'], text=True).stdout
        assert not re.search(r'\btcp:443\b', current), 'Existing reverse on port 443'
        interop.GRADLE = [sys.executable, str(interop.ROOT/'scripts/verify_webrtc_network_lab.py')]
        leaf = 'untrusted' if args.invalid_certificate else 'valid'
        lab = interop.Build('lab', [case, '--turnserver', str(Path(args.turnserver).resolve()),
                                  '--external-client-dir', str(output), '--tls-cert', str(fixture/(leaf+'.pem')),
                                  '--tls-key', str(fixture/(leaf+'.key'))], dict(os.environ))
        builds.append(lab)
        deadline = time.monotonic()+650
        while not (output/'ready').exists():
            lab.remaining()
            if lab.process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('TLS lab did not become ready; see lab.log and ../compile.log')
            time.sleep(.25)
        print('Isolated TURN and native Session endpoint ready', flush=True)
        android(['install', '-r', '-t', str(interop.ROOT/'core/build/outputs/apk/androidTest/core-androidTest.apk')])
        installed = True
        android(['shell', 'run-as', PACKAGE, 'mkdir', '-p', FIXTURES])
        configured = True
        for file in fixture.glob('*.der'):
            private_file(FIXTURES+'/'+file.name, file.read_bytes())
        private_file(CONFIG, (output/'client-config.json').read_bytes())
        ports = []
        for name in ('http', 'tls'):
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            interop.GRADLE = ['ncat']
            forward = interop.Build(name+'-host-forward', ['--listen', '127.0.0.1', str(port), '--keep-open',
                '--sh-exec', 'exec /usr/bin/ncat --unixsock '+shlex.quote(str(output/(name+'.sock')))], dict(os.environ))
            builds.append(forward)
            ports.append(port)
        http_port, tls_port = ports
        time.sleep(.2)
        control = dict(secure=True)
        try:
            with socket.create_connection(('127.0.0.1', tls_port), timeout=3) as tcp:
                with ssl.create_default_context(cafile=str(fixture/'ca.pem')).wrap_socket(tcp, server_hostname='127.0.0.1') as tls:
                    assert not args.invalid_certificate, 'Untrusted certificate was accepted'
                    control.update(verified=True, version=tls.version())
        except ssl.SSLCertVerificationError as error:
            assert args.invalid_certificate
            control.update(verified=False, verifyCode=error.verify_code)
        print('Strict TLS certificate control checked: '+json.dumps(control), flush=True)
        for remote, local in ((443, tls_port), (http_port, http_port)):
            android(['reverse', '--no-rebind', 'tcp:'+str(remote), 'tcp:'+str(local)])
            reverses.append(remote)
        uid = android(['shell', 'stat', '-c', '%u', '/data/user/0/'+PACKAGE], text=True).stdout.strip()
        assert uid.isdecimal() and int(uid) >= 10000
        chain = 'FSPAN_TLS_'+str(os.getpid())
        for tool in ('iptables', 'ip6tables'):
            android(['shell', tool, '-w', '-N', chain])
            chains.append((tool, False))
            if tool == 'iptables':
                for port in (443, http_port):
                    android(['shell', tool, '-w', '-A', chain, '-p', 'tcp', '-d', '127.0.0.1', '--dport', str(port), '-j', 'RETURN'])
            android(['shell', tool, '-w', '-A', chain, '-p', 'udp', '-j', 'DROP'])
            android(['shell', tool, '-w', '-A', chain, '-j', 'DROP'])
            android(['shell', tool, '-w', '-I', 'OUTPUT', '1', '-m', 'owner', '--uid-owner', uid, '-j', chain])
            chains[-1] = (tool, True)
        interop.GRADLE = adb
        test = interop.Build('android-test', ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.folderspan.service.webrtc.WebRtcInteropTlsTrustTest,com.folderspan.service.webrtc.WebRtcInteropBrowserTest',
            '-e', 'webrtcInteropTlsFixtures', f'/data/user/0/{PACKAGE}/{FIXTURES}',
            '-e', 'webrtcInteropUrl', f'http://127.0.0.1:{http_port}', PACKAGE+'/androidx.test.runner.AndroidJUnitRunner'], dict(os.environ))
        builds.append(test)
        deadline = time.monotonic()+15
        while True:
            pid = subprocess.run(adb+['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=3).stdout.strip()
            if pid: break
            if test.process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Android TLS instrumentation did not start')
            time.sleep(.1)
        logcat = interop.Build('android-logcat', ['logcat', '--pid='+pid, '-v', 'brief', 'System.out:I', 'TestRunner:I', 'openssl_adapter.cc:W', '*:S'], dict(os.environ))
        builds.append(logcat)
        test.wait(timeout=90, on_tick=lambda: [b.remaining() for b in builds])
        logcat.close()
        builds.remove(logcat)
        test_log = (output/'android-test.log').read_text()
        client_log = (output/'android-logcat.log').read_text()
        assert 'WEBRTC_TLS_TRUST valid=true wrongHostRejected=true expiredRejected=true untrustedRejected=true' in client_log
        assert 'WEBRTC_TLS_FACTORY secureVerifierInjected=true' in client_log
        if args.invalid_certificate:
            assert 'Failures: 1' in test_log and 'WEBRTC_INTEROP_FAILED' in client_log
            assert 'WEBRTC_ICE_CLIENT_PATH' not in client_log
            local_path = None
        else:
            assert 'OK (2 tests)' in test_log
            paths = [json.loads(line.split('WEBRTC_ICE_CLIENT_PATH ', 1)[1]) for line in client_log.splitlines() if 'WEBRTC_ICE_CLIENT_PATH ' in line]
            assert len(paths) == 1
            local_path = paths[0]
            assert local_path['kind'] == 'Relay' and local_path['localTurnTransport'] == 'Tls'
        counters, udp_counters = {}, {}
        for tool, _ in chains:
            rules = android(['shell', tool, '-w', '-L', chain, '-n', '-v', '-x'], text=True).stdout
            (output/('android-'+tool+'.txt')).write_text(rules)
            counters[tool] = sum(int(line.split()[0]) for line in rules.splitlines() if 'DROP' in line)
            udp_counters[tool] = sum(int(line.split()[0]) for line in rules.splitlines() if 'DROP' in line and 'udp' in line)
        assert sum(udp_counters.values()) > 0, 'Client UDP blocking was not exercised'
        (output/'client-complete.json').write_text(json.dumps(dict(exitCode=1 if args.invalid_certificate else 0)))
        lab.wait(timeout=40, on_tick=lambda: [b.remaining() for b in builds])
        turn_log = (output/'turn.log').read_text()
        tls_sessions = set(re.findall(r'session (\d+):.*local 203\.0\.113\.10:443,', turn_log))
        allocations = set(re.findall(r'session (\d+):.*ALLOCATE processed, success', turn_log))
        tls_allocations = len(tls_sessions & allocations)
        assert (tls_allocations == 0) if args.invalid_certificate else (tls_allocations > 0)
        result = dict(json.loads((output/'result.json').read_text()), androidSerial=args.serial,
                      clientLocalPathAfterRestart=local_path, tlsControl=control, blockedClientPackets=counters,
                      blockedClientUdpPackets=udp_counters, serverTlsPort=443, serverTlsAllocationSessions=tls_allocations,
                      trust='isolated fixture CA; chain, validity and DNS/IP SAN checked; production trust unchanged')
    finally:
        errors = []
        for tool, jumped in reversed(chains):
            try:
                rules = android(['shell', tool, '-w', '-L', chain, '-n', '-v', '-x'], text=True).stdout
                (output/('android-'+tool+'.txt')).write_text(rules)
                if jumped:
                    android(['shell', tool, '-w', '-D', 'OUTPUT', '-m', 'owner', '--uid-owner', uid, '-j', chain])
                android(['shell', tool, '-w', '-F', chain])
                android(['shell', tool, '-w', '-X', chain])
            except Exception as error: errors.append(str(error))
        if installed:
            try:
                android(['shell', 'am', 'force-stop', PACKAGE])
                assert not subprocess.run(adb+['shell', 'pidof', PACKAGE], capture_output=True, text=True, timeout=10).stdout.strip()
            except Exception as error: errors.append(str(error))
        for port in reverses:
            try: android(['reverse', '--remove', 'tcp:'+str(port)])
            except Exception as error: errors.append(str(error))
        if configured:
            try: android(['shell', 'run-as', PACKAGE, 'rm', '-rf', FIXTURES, CONFIG])
            except Exception as error: errors.append(str(error))
        for build in reversed(builds):
            try:
                build.close()
                assert not build.remaining()
            except Exception as error: errors.append(str(error))
        for name in ('http.sock', 'tls.sock', 'client-config.json', 'ready'):
            (output/name).unlink(missing_ok=True)
        if rooted:
            try:
                # adbd may close its control connection while successfully restarting without root.
                subprocess.run(adb+['unroot'], timeout=20, capture_output=True)
                android(['wait-for-device'])
                assert android(['shell', 'id', '-u'], text=True).stdout.strip() != '0'
            except Exception as error: errors.append(str(error))
        for path in fixture.glob('*.key'): path.unlink()
        if errors: raise RuntimeError('; '.join(errors))
    result['clientProcessesStopped'] = True
    result['clientNetworkRestored'] = True
    (output/'android-result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
