# Sunshine Mobile (scaffold)

Android phone as a Moonlight host.

Working now: MediaProjection capture, hardware H.264 encode, mDNS advertising
(_nvstream._tcp), minimal /serverinfo on port 47989.

Not implemented yet: pairing (/pair, crypto), HTTPS port 47984, /applist, /launch,
RTSP, RTP video + FEC, audio, control channel, input injection.

Build: push to GitHub, open Actions > "Build Sunshine Mobile APK" > download artifact.
