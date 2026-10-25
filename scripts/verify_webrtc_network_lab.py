#!/usr/bin/env python3
"""Verify WebRTC through isolated Linux NAT, IPv6 and TURN networks."""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import shutil
import socket
import ssl
import subprocess
import sys
from threading import Thread
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / 'build/webrtc-ice-lab'
SCENARIOS = ('ipv4-nat', 'direct-with-turn', 'lan-stun-unreachable', 'ipv6-only',
             'ipv6-blackhole', 'turn-udp', 'turn-udp-cancel', 'turn-tcp', 'turn-invalid-tls',
             'turn-invalid-credentials', 'turn-unreachable', 'no-path', 'network-switch',
             'turn-tls', 'turn-tls-invalid')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('scenarios', nargs='+', choices=SCENARIOS)
parser.add_argument('--turnserver', default=shutil.which('turnserver'), help='Existing coturn executable; no packages are installed')
parser.add_argument('--external-client-dir', type=Path, help='TLS test client completion/config directory')
parser.add_argument('--tls-cert', type=Path)
parser.add_argument('--tls-key', type=Path)
parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
args = parser.parse_args()
if any(case in ('turn-tls', 'turn-tls-invalid') for case in args.scenarios):
    if len(args.scenarios) != 1 or not args.external_client_dir or not args.tls_cert or not args.tls_key:
        parser.error('TLS scenarios require one external client, certificate and key')
    args.external_client_dir = args.external_client_dir.resolve()
    args.tls_cert, args.tls_key = args.tls_cert.resolve(), args.tls_key.resolve()
    if not args.tls_cert.is_file() or not args.tls_key.is_file():
        parser.error('TLS certificate/key files must exist')
elif args.external_client_dir or args.tls_cert or args.tls_key:
    parser.error('External TLS inputs only apply to turn-tls / turn-tls-invalid')
LAB.mkdir(parents=True, exist_ok=True)
if any(case != 'lan-stun-unreachable' for case in args.scenarios):
    if not args.turnserver or not os.access(args.turnserver, os.X_OK):
        parser.error('--turnserver must name an executable coturn binary')
    args.turnserver = str(Path(args.turnserver).resolve())

if not args.inside:
    from verify_webrtc_interop import Build
    import verify_webrtc_interop as interop
    interop.OUTPUT = LAB
    init = LAB/'classpath.init.gradle'
    init.write_text('''gradle.projectsEvaluated {
        def core = gradle.rootProject.project(":core")
        core.tasks.register("writeIceLabClasspath") {
            doLast {
                new File(core.rootDir, "build/webrtc-ice-lab/classpath.txt").text =
                    core.tasks.getByName("jvmTest").classpath.asPath
            }
        }
    }
    ''')
    tasks = ['--no-configuration-cache', '-I', str(init),
             ':core:compileTestKotlinJvm', ':core:writeIceLabClasspath']
    if args.external_client_dir:
        tasks.append(':core:assembleAndroidDeviceTest')
    build = Build('compile', tasks, dict(os.environ))
    try:
        build.wait(timeout=600)
    finally:
        build.close()
    for case in args.scenarios:
        command = ['unshare', '--user', '--map-root-user', '--net', sys.executable,
                   str(Path(__file__).resolve()), '--inside', case]
        if args.turnserver:
            command += ['--turnserver', args.turnserver]
        if args.external_client_dir:
            command += ['--external-client-dir', str(args.external_client_dir),
                        '--tls-cert', str(args.tls_cert), '--tls-key', str(args.tls_key)]
        subprocess.run(command, check=True, cwd=ROOT)
    sys.exit(0)

# Refuse an existing network: this script may only modify a fresh, single-user namespace.
if (os.getuid() != 0 or Path('/proc/self/uid_map').read_text().split()[2:] != ['1']
        or socket.if_nameindex() != [(1, 'lo')] or len(args.scenarios) != 1):
    parser.error('internal runner requires a fresh single-user network namespace')
args.scenario = args.scenarios[0]
OUT = LAB / args.scenario
OUT.mkdir(exist_ok=True)
(OUT/'result.json').unlink(missing_ok=True)
processes = []
logs = []

def run(argv, pid=None, **kw):
    if pid:
        argv = ['nsenter', '-t', str(pid), '-n'] + argv
    result = subprocess.run(argv, capture_output=True, text=True, **kw)
    if result.returncode:
        raise RuntimeError(f'{argv}: {result.stderr}')
    return result

def start(argv, name, env=None, pid=None):
    if pid:
        argv = ['nsenter', '-t', str(pid), '-n'] + argv
    log = (OUT / (name + '.log')).open('w')
    logs.append(log)
    process = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    processes.append(process)
    return process

def namespace(name):
    p = start(['unshare', '--net', 'sleep', '300'], name)
    for _ in range(100):
        if os.readlink(f'/proc/{p.pid}/ns/net') != os.readlink('/proc/self/ns/net'):
            break
        time.sleep(.01)
    else:
        raise RuntimeError('Network namespace not created')
    run(['ip', 'link', 'set', 'lo', 'up'], p.pid)
    return p.pid

def link(name, left, right, left_address, right_address, v6left=None, v6right=None):
    run(['ip', 'link', 'add', name+'l', 'type', 'veth', 'peer', 'name', name+'r'])
    for suffix, pid, address, v6 in [('l', left, left_address, v6left), ('r', right, right_address, v6right)]:
        iface = name + suffix
        if pid:
            run(['ip', 'link', 'set', iface, 'netns', str(pid)])
        run(['ip', 'link', 'set', iface, 'up'], pid)
        if address:
            run(['ip', 'address', 'add', address, 'dev', iface], pid)
        if v6:
            run(['ip', '-6', 'address', 'add', v6, 'dev', iface, 'nodad'], pid)

def rules(text, pid=None):
    run(['nft', '-f', '-'], pid, input=text.replace('; }', '; }\n')+'\n')

def forwarding(pid=None):
    run(['sysctl', '-q', '-w', 'net.ipv4.ip_forward=1', 'net.ipv6.conf.all.forwarding=1'], pid)

success = args.scenario not in ('turn-invalid-tls', 'turn-invalid-credentials', 'turn-unreachable', 'no-path', 'turn-tls-invalid')
tls_control_verify_code = None
recovery_bridge = None
bridge_thread = None
try:
    run(['ip', 'link', 'set', 'lo', 'up'])
    a, b = namespace('a'), namespace('b')
    ipv6 = args.scenario in ('ipv6-only', 'ipv6-blackhole')
    only6 = args.scenario == 'ipv6-only'
    lan = args.scenario == 'lan-stun-unreachable'
    if lan:
        run(['ip', 'link', 'add', 'lan', 'type', 'bridge'])
        run(['ip', 'address', 'add', '10.60.0.1/24', 'dev', 'lan'])
        run(['ip', 'link', 'set', 'lan', 'up'])
        for i, pid in enumerate([a, b], 2):
            name = f'lan{i}'
            link(name, None, pid, None, f'10.60.0.{i}/24')
            run(['ip', 'link', 'set', name+'l', 'master', 'lan'])
        bridge = 'http://10.60.0.2:18390'
        stun = 'stun:10.60.0.1:3479'
        rules('table inet drop_stun { chain input { type filter hook input priority 0; udp dport 3479 counter drop; } }')
        turn_ip = '10.60.0.1'
    else:
        ra, rb = namespace('ra'), namespace('rb')
        for i, (router, endpoint, net, public) in enumerate([(ra, a, 51, '192.0.2'), (rb, b, 52, '198.51.100')], 1):
            link(f'w{i}', None, router, None if only6 else f'{public}.1/24', None if only6 else f'{public}.2/24', f'2001:db8:{i+10}::1/64' if ipv6 else None, f'2001:db8:{i+10}::2/64' if ipv6 else None)
            link(f'l{i}', router, endpoint, None if only6 else f'10.{net}.0.1/24', None if only6 else f'10.{net}.0.2/24', f'2001:db8:{i}::1/64' if ipv6 else None, f'2001:db8:{i}::2/64' if ipv6 else None)
            if not only6:
                run(['ip', 'route', 'add', 'default', 'via', f'{public}.1'], router)
                run(['ip', 'route', 'add', 'default', 'via', f'10.{net}.0.1'], endpoint)
                rules(f'''table ip nat {{ chain postrouting {{ type nat hook postrouting priority srcnat; oifname "w{i}r" masquerade; }} chain prerouting {{ type nat hook prerouting priority dstnat; iifname "w{i}r" tcp dport 18390 dnat to 10.{net}.0.2; }} }}''', router)
                # Admit return traffic only for UDP ports opened by outbound packets.
                if args.scenario in ('ipv4-nat', 'ipv6-blackhole', 'direct-with-turn', 'network-switch'):
                    rules(f'''add set ip nat udp_mappings {{ type inet_service; flags dynamic,timeout; timeout 60s; }}
insert rule ip nat postrouting oifname "w{i}r" meta l4proto udp add @udp_mappings {{ udp sport timeout 60s }} masquerade
insert rule ip nat prerouting iifname "w{i}r" udp dport @udp_mappings dnat to 10.{net}.0.2''', router)
            if ipv6:
                run(['ip', '-6', 'route', 'add', 'default', 'via', f'2001:db8:{i+10}::1'], router)
                run(['ip', '-6', 'route', 'add', 'default', 'via', f'2001:db8:{i}::1'], endpoint)
                run(['ip', '-6', 'route', 'add', f'2001:db8:{i}::/64', 'via', f'2001:db8:{i+10}::2'])
            forwarding(router)
        forwarding()
        # Silent loss prevents ENETUNREACH from closing an otherwise usable TURN allocation.
        run(['ip', 'link', 'add', 'discard0', 'type', 'dummy'])
        run(['ip', 'link', 'set', 'discard0', 'up'])
        run(['ip', 'route', 'add', 'default', 'dev', 'discard0'])
        if ipv6:
            run(['ip', '-6', 'route', 'add', 'default', 'dev', 'discard0'])
        run(['ip', 'address', 'add', '203.0.113.10/32', 'dev', 'lo'])
        if only6:
            run(['ip', '-6', 'address', 'add', '2001:db8:ffff::10/128', 'dev', 'lo', 'nodad'])
        bridge = 'http://[2001:db8:1::2]:18390' if only6 else 'http://192.0.2.2:18390'
        stun = 'stun:[2001:db8:ffff::10]:3478' if only6 else 'stun:203.0.113.10:3478'
        turn_ip = '2001:db8:ffff::10' if only6 else '203.0.113.10'
        if args.scenario == 'ipv6-blackhole':
            rules('table inet blackhole { chain forward { type filter hook forward priority 0; meta nfproto ipv6 counter drop; } }')
        if args.scenario.startswith('turn-') or args.scenario == 'no-path':
            rules('table inet block_direct { chain forward { type filter hook forward priority 0; tcp dport 18390 accept; tcp sport 18390 ct state established accept; counter drop; } }')
        if args.scenario in ('turn-tcp', 'turn-invalid-tls', 'turn-tls', 'turn-tls-invalid'):
            for pid in (a, b):
                rules('table inet no_udp { chain output { type filter hook output priority 0; oifname != "lo" meta l4proto udp counter drop; } }', pid)
    password = os.urandom(16).hex()
    turn = ''
    if args.scenario.startswith('turn-') or args.scenario in ('direct-with-turn', 'network-switch'):
        scheme = 'turns' if args.scenario == 'turn-invalid-tls' else 'turn'
        transport = 'tcp' if args.scenario in ('turn-tcp', 'turn-invalid-tls', 'turn-unreachable', 'turn-tls', 'turn-tls-invalid') else 'udp'
        turn_port = 443 if scheme == 'turns' else 3478
        if args.scenario == 'turn-unreachable':
            turn_port = 3479
        turn = f'{scheme}:{turn_ip}:{turn_port}?transport={transport}'
    if not lan:
        cert, key = args.tls_cert or OUT/'cert.pem', args.tls_key or OUT/'key.pem'
        if not args.tls_cert:
            run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(cert), '-days', '1', '-subj', '/CN=untrusted.lab.test'])
        cfg = OUT/'turn.conf'
        cfg.write_text(f'''listening-ip={turn_ip}
relay-ip={turn_ip}
listening-port=3478
tls-listening-port=443
min-port=49160
max-port=49200
realm=lab.test
lt-cred-mech
user=labuser:{password}
fingerprint
allow-loopback-peers
no-multicast-peers
no-cli
no-dtls
cert={cert}
pkey={key}
log-file=stdout
simple-log
verbose
pidfile={OUT/'turn.pid'}
userdb={OUT/'turn.sqlite'}
''')
        cfg.chmod(0o600)
        turn_process = start([args.turnserver, '-c', str(cfg), '--relay-threads=1'], 'turn')
        time.sleep(.5)
        assert turn_process.poll() is None, 'TURN server exited'
        if args.scenario == 'turn-invalid-tls':
            with socket.create_connection((turn_ip, 443), timeout=3) as tcp:
                try:
                    ssl.create_default_context().wrap_socket(tcp, server_hostname=turn_ip)
                except ssl.SSLCertVerificationError as error:
                    tls_control_verify_code = error.verify_code
                    assert error.verify_code == 18, 'Expected the generated self-signed certificate'
                else:
                    raise AssertionError('Self-signed TURN certificate was accepted')
        if not only6 and args.scenario not in ('turn-tcp','turn-invalid-tls','turn-tls','turn-tls-invalid'):
            probe = '''import socket,os
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(2)
s.sendto(b'\\x00\\x01\\x00\\x00\\x21\\x12\\xa4\\x42'+os.urandom(12),('203.0.113.10',3478))
reply=s.recv(2048)
assert reply[:2]==b'\\x01\\x01'
print('STUN binding response verified')'''
            for pid in (a, b):
                print(run(['python3', '-c', probe], pid).stdout.strip(), flush=True)
    config = dict(
        stunUrl=stun, turnUrl=turn, turnUsername='labuser' if turn else '',
        turnPassword=('invalid' if args.scenario == 'turn-invalid-credentials' else password) if turn else '',
        expectedPath='Relay' if turn and args.scenario not in ('direct-with-turn', 'network-switch') else 'Direct',
        expectedFamily='IPv6' if only6 else 'IPv4', expectedProtocol='Udp', networkScenario=args.scenario,
    )
    if args.external_client_dir:
        config['stunUrl'] = ''
    start(['tcpdump', '-Z', 'root', '-l', '-n', '-i', 'any', 'udp or tcp port 3478 or tcp port 443'], 'packets')
    env = dict(
        os.environ, FOLDERSPAN_WEBRTC_INTEROP_PORT='18390',
        FOLDERSPAN_WEBRTC_INTEROP_BIND='::' if only6 else '0.0.0.0',
        FOLDERSPAN_WEBRTC_INTEROP_URL=bridge, FOLDERSPAN_WEBRTC_INTEROP_CONFIG=json.dumps(config),
    )
    home = OUT/'home'
    home.mkdir(exist_ok=True)
    hosts = OUT/'hosts'
    hosts.write_text('127.0.0.1 localhost '+socket.gethostname()+'\n::1 localhost\n')
    java = ['java', '-Xmx1g', '-Djava.awt.headless=true', '-Djdk.net.hosts.file='+str(hosts), '-Duser.home='+str(home), '-Djava.util.prefs.userRoot='+str(home/'prefs'), '-cp', (LAB/'classpath.txt').read_text(), 'org.junit.runner.JUnitCore']
    if args.scenario == 'network-switch':
        from verify_webrtc_android_pair import Bridge
        # Keep signaling reachable while the media interface loses its address.
        for i, pid in enumerate((a, b), 1):
            link(f's{i}', None, pid, f'10.{70+i}.0.1/24', f'10.{70+i}.0.2/24')
            rules(f'''table inet signaling_only {{
chain output {{ type filter hook output priority 0; oifname "s{i}r" tcp dport 18390 accept; oifname "s{i}r" counter drop; }}
chain input {{ type filter hook input priority 0; iifname "s{i}r" tcp sport 18390 ct state established accept; iifname "s{i}r" counter drop; }}
}}''', pid)
        recovery_bridge = Bridge(('0.0.0.0', 18390))
        recovery_bridge.values.update({'server-state': b'', 'client-state': b'', 'control': b''})
        bridge_thread = Thread(target=recovery_bridge.serve_forever, daemon=True)
        bridge_thread.start()
        bridge = 'http://10.71.0.1:18390'
        env['FOLDERSPAN_WEBRTC_INTEROP_URL'] = bridge
        env['FOLDERSPAN_WEBRTC_RECOVERY_ROLE'] = 'server'
        # Separate preferences keep the two production device identities distinct.
        java_server = [value.replace(str(home), str(home/'server')) for value in java]
        java_client = [value.replace(str(home), str(home/'client')) for value in java]
        native = start(java_server+['com.folderspan.service.webrtc.WebRtcControllerNetworkJvmTest'], 'native', env, a)
    else:
        native = start(java+['com.folderspan.service.webrtc.WebRtcInteropJvmTest'], 'native', env, a)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 40
    while args.scenario != 'network-switch':
        try:
            with opener.open(bridge+'/ready', timeout=1) as response:
                if response.read() == b'ready':
                    break
        except (OSError, TimeoutError):
            if native.poll() is not None:
                raise RuntimeError('Native bridge exited')
            if time.monotonic() > deadline:
                raise RuntimeError('Native bridge did not start')
            time.sleep(.1)
    if args.scenario == 'network-switch':
        client = start(java_client+['com.folderspan.service.webrtc.WebRtcControllerNetworkJvmTest'], 'client', dict(env, FOLDERSPAN_WEBRTC_RECOVERY_ROLE='client', FOLDERSPAN_WEBRTC_INTEROP_URL='http://10.72.0.1:18390'), b)
    elif args.external_client_dir:
        external = args.external_client_dir
        external.mkdir(parents=True, exist_ok=True)
        for name in ('http.sock', 'tls.sock'):
            assert not (external/name).exists(), 'Existing external client socket'
        start(['ncat', '--unixsock', '--listen', str(external/'http.sock'), '--keep-open',
               '--sh-exec', 'exec /usr/bin/ncat 192.0.2.2 18390'], 'http-forward')
        start(['ncat', '--unixsock', '--listen', str(external/'tls.sock'), '--keep-open',
               '--sh-exec', 'exec /usr/bin/ncat 203.0.113.10 443'], 'tls-forward')
        client_config = dict(config, turnUrl='turns:localhost:443?transport=tcp')
        configuration = external/'client-config.json'
        configuration.write_text(json.dumps(client_config))
        configuration.chmod(0o600)
        (external/'ready').write_text('ready')
        waiter = '''import json,sys,time
from pathlib import Path
p=Path(sys.argv[1]); deadline=time.monotonic()+120
while not p.exists():
 if time.monotonic()>deadline: raise TimeoutError('External TLS client did not finish')
 time.sleep(.1)
result=json.loads(p.read_text());print(Path(sys.argv[2]).read_text(),flush=True)
sys.exit(result['exitCode'])'''
        client = start(['python3', '-c', waiter, str(external/'client-complete.json'),
                        str(external/'android-logcat.log')], 'client')
    else:
        client = start(java+['com.folderspan.service.webrtc.WebRtcInteropBrowserTest'], 'client', env, b)
    started = time.monotonic()
    deadline = started + 90
    if recovery_bridge:
        while not all(json.loads(recovery_bridge.values[role+'-state'] or b'{}').get('stage') == 'ready' for role in ('server', 'client')):
            if native.poll() is not None or client.poll() is not None or time.monotonic() - started > 40:
                raise RuntimeError('Controllers did not reach an authenticated direct Session')
            time.sleep(.05)
        (OUT/'endpoint-a-before-addresses.txt').write_text(run(['ip', '-brief', 'address'], a).stdout)
        rules('table inet block_direct { chain forward { type filter hook forward priority 0; counter drop; } }')
        rules('table inet transition { chain input { type filter hook input priority 0; meta l4proto udp counter drop; } }')
        old_rules = run(['nft', 'list', 'ruleset'], ra).stdout
        rules('flush ruleset\n' + old_rules.replace('10.51.0.2', '10.51.0.3'), ra)
        run(['ip', 'address', 'del', '10.51.0.2/24', 'dev', 'l1r'], a)
        run(['ip', 'address', 'add', '10.51.0.3/24', 'dev', 'l1r'], a)
        run(['ip', 'route', 'replace', 'default', 'via', '10.51.0.1'], a)
        recovery_deadline = time.monotonic() + 25
        while not all(json.loads(recovery_bridge.values[role+'-state'] or b'{}').get('stage') == 'recovering' for role in ('server', 'client')):
            if native.poll() is not None or client.poll() is not None or time.monotonic() > recovery_deadline:
                raise RuntimeError('Native network loss did not enter controller recovery')
            time.sleep(.05)
        (OUT/'transition-rules.txt').write_text(run(['nft', 'list', 'ruleset']).stdout)
        run(['nft', 'delete', 'table', 'inet', 'transition'])
        recovery_bridge.values['control'] = b'changed'
        print('Endpoint address changed; direct path blocked; signaling and TURN remain reachable', flush=True)
    while native.poll() is None or client.poll() is None:
        if time.monotonic() > deadline:
            raise TimeoutError('Network scenario did not finish')
        time.sleep(.1)
    duration = time.monotonic() - started
    codes = [native.returncode, client.returncode]
    counters = {}
    nodes = [('transit', None), ('endpoint-a', a), ('endpoint-b', b)]
    if not lan:
        nodes += [('router-a', ra), ('router-b', rb)]
    for name, pid in nodes:
        rule_text = run(['nft', 'list', 'ruleset'], pid).stdout
        (OUT/(name+'-rules.txt')).write_text(rule_text)
        (OUT/(name+'-addresses.txt')).write_text(run(['ip', '-brief', 'address'], pid).stdout)
        counters[name] = sum(int(count) for count in re.findall(r'counter packets (\d+)', rule_text))
    native_log = (OUT/'native.log').read_text()
    client_log = (OUT/'client.log').read_text()
    if args.scenario in ('turn-tcp', 'turn-invalid-tls', 'turn-tls', 'turn-tls-invalid'):
        assert counters['endpoint-a'] > 0
        if not args.external_client_dir:
            assert counters['endpoint-b'] > 0
    if args.scenario in ('ipv6-blackhole', 'lan-stun-unreachable'):
        assert counters['transit'] > 0
    if recovery_bridge:
        assert codes == [0, 0], codes
        assert counters['transit'] > 0 and counters['endpoint-a'] > 0 and counters['endpoint-b'] > 0
        observations = [json.loads(recovery_bridge.values[role+'-state']) for role in ('server', 'client')]
        assert all(item['stage'] == 'finished' and item['restartRounds'] == 1 for item in observations)
        assert all(item['before']['path']['kind'] == 'Direct' and item['after']['path']['kind'] == 'Relay' and item['after']['negotiation'] == 1 for item in observations)
        assert all(item['sameAuthorization'] and item['sameChannel'] and item['sameSession'] and item['samePeerConnection'] for item in observations)
        assert all(item['permanentCloseObserved'] and item['newConnectionAuthorized'] and item['cancelledNewConnection'] for item in observations)
        assert observations[0]['authentications'] == 1 and observations[0]['offerCount'] == 2
        assert observations[0]['totalAuthenticationsAfterNewConnection'] == 2
        assert observations[1]['offerCount'] == 0 and observations[1]['rpcVerified']
    elif success:
        assert codes == [0, 0], codes
        observations = [json.loads(line.split(' ', 1)[1]) for line in native_log.splitlines() if line.startswith('WEBRTC_ICE_EVIDENCE ')]
        assert {v['observation'].get('source', 'Local') for v in observations} == {'Local','Remote'}
        assert all(v['rpcVerified'] and v['iceRestartApplied'] for v in observations)
        assert all(v['observation']['path']['kind'] == config['expectedPath'] for v in observations)
        if args.scenario == 'ipv6-blackhole':
            assert all('family=IPv6 protocol=udp' in log for log in (native_log, client_log))
        if args.scenario in ('ipv4-nat', 'direct-with-turn'):
            assert all('protocol=udp type=srflx' in log for log in (native_log, client_log))
        if args.scenario == 'direct-with-turn':
            assert all('protocol=udp type=relay' in log for log in (native_log, client_log))
        if args.scenario == 'turn-tcp':
            assert observations[0]['observation']['path']['localTurnTransport'] == 'Tcp'
            assert (OUT/'turn.log').read_text().count('tcp or tls connected to:') >= 2
        if args.scenario == 'turn-udp-cancel':
            assert 'WEBRTC_INTEROP_CANCEL pendingRelayAllocated=true closed=true activeRpcVerified=true activeFileVerified=true' in client_log
    else:
        assert all(code != 0 for code in codes), codes
        failures = native_log + client_log
        assert failures.count('WEBRTC_INTEROP_FAILED') == 2
        assert 'error=TimeoutCancellationException' in failures
        assert duration < 60, duration
        assert 'channel=open' not in failures
        assert 'protocol=udp type=relay' not in client_log
        if not args.external_client_dir:
            assert 'protocol=udp type=relay' not in native_log
        if args.scenario == 'turn-invalid-tls':
            turn_log = (OUT/'turn.log').read_text()
            assert turn_log.count('tcp or tls connected to:') >= 3
            assert 'incoming packet ALLOCATE processed, success' not in turn_log
        observations = []
    result = dict(
        scenario=args.scenario, scope=('isolated Linux kernel network with external test client' if args.external_client_dir
                                      else 'isolated Linux kernel networks; no public-network claim'),
        passed=True, expectedSuccess=success, exitCodes=codes, seconds=round(duration, 2),
        blockedPackets=counters, observations=observations,
    )
    if not success:
        result['iceServiceErrorCodes'] = re.findall(r'iceServiceCode=(\d+|null)', native_log + client_log)
    if tls_control_verify_code is not None:
        result['tlsControlVerifyCode'] = tls_control_verify_code
finally:
    if recovery_bridge:
        recovery_bridge.shutdown()
        bridge_thread.join(timeout=5)
        recovery_bridge.server_close()
        assert not bridge_thread.is_alive()
    for process in reversed(processes):
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
            if process.poll() is not None:
                break
            try:
                os.killpg(process.pid, sig)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                if sig == signal.SIGKILL:
                    raise RuntimeError('Lab process failed to exit')
    for log in logs:
        log.close()
    if args.external_client_dir:
        for name in ('http.sock', 'tls.sock', 'client-config.json', 'ready'):
            (args.external_client_dir/name).unlink(missing_ok=True)
    print('lab processes stopped', flush=True)

assert all(process.poll() is not None for process in processes)
result['processesStopped'] = True
(OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result), flush=True)
