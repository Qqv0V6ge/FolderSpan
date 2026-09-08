// Chrome restricts ICE to the default route until media permission is granted.
// With a VPN that route may be an unreachable virtual NIC. The test launcher
// supplies fake devices, so this hook never opens a real microphone or camera.
before(async function () {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    stream.getTracks().forEach(track => track.stop());
});
