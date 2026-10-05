//go:build linux

package main

import "github.com/amnezia-vpn/amneziawg-go/v3/tun"

// openTunFD takes over a TUN descriptor somebody else opened — on Android, the VpnService's. The
// interface's addresses, routes and MTU were set when it was built, so there is nothing to
// configure here: packets in, packets out, exactly what amneziawg-android does with the same fd.
func openTunFD(fd int) (tun.Device, error) {
	dev, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	return dev, err
}
