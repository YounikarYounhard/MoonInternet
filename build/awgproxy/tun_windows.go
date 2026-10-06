package main

import (
	"errors"

	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// openWintun creates the adapter the Windows TUN mode runs on. Only the adapter: its address, DNS
// and routes are set by the SYSTEM helper once it is up, the same way it sets them for tun2socks.
// wintun.dll has to sit beside the exe — the loader looks nowhere else but System32.
func openWintun(name string, mtu int) (tun.Device, error) {
	tun.WintunTunnelType = "Moon Internet"
	return tun.CreateTUN(name, mtu)
}

func openTunFD(int) (tun.Device, error) {
	return nil, errors.New("-tunfd is for Android, where the VpnService hands one over")
}
