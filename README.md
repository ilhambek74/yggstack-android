# Yggstack Android - Yggdrasil as SOCKS proxy / port forwarder
[🇷🇺 Русский](README_RU.md) | [🇬🇧 English](#)

## Description

This is a full-featured Android UI wrapper for the [Yggstack](https://github.com/yggdrasil-network/yggstack) CLI application.

Yggstack provides SOCKS5 proxy server and TCP port forwarder functionality over Yggdrasil network similar to a TOR router. It can also serve as a standalone Yggdrasil network node to connect network segments.

* No VPN / TUN adapter needed
* No root / administrator access required
* Web browser access
* TCP/UDP port forwarder

Check the upstream yggstack [README.md](https://github.com/yggdrasil-network/yggstack) file for more details.

## Screenshots

<div style="overflow-x: auto; white-space: nowrap; padding: 10px 0;">
  <img src="screenshots/img1.jpeg" alt="Screenshot 1" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img3.jpeg" alt="Screenshot 2" width="250" style="display: inline-block;"/>
</div>



## Use cases
### Enable SOCKS5 proxy to get access to yggdrasil websites. Powered by [Alfis](https://alfis.name) ([dns servers](https://dns.r3v.dev/)).  
Example: Firefox + Proxy Toggle extension.
<div style="overflow-x: auto; white-space: nowrap; padding: 10px 0;">
  <img src="screenshots/img2.jpeg" alt="Screenshot 1" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img5.jpeg" alt="Screenshot 2" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img4.jpeg" alt="Screenshot 2" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img6.jpeg" alt="Screenshot 3" width="250" style="display: inline-block;"/>
</div>

### Forward telegram port with default android client.
<div style="overflow-x: auto; white-space: nowrap; padding: 10px 0;">
  <img src="screenshots/img7.jpeg" alt="Screenshot 1" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img8.jpeg" alt="Screenshot 1" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img9.jpeg" alt="Screenshot 1" width="250" style="margin-right: 15px; display: inline-block;"/>
  <img src="screenshots/img10.jpeg" alt="Screenshot 3" width="250" style="display: inline-block;"/>
</div>

## Communities
Several IRC communities exist, including the #yggdrasil IRC channel on libera.chat and various others on Yggdrasil-internal IRC networks.  
The Russian community is available on Telegram: https://t.me/Yggdrasil_ru

## License

This code is released under the terms of the LGPLv3, but with an added exception
that was shamelessly taken from [godeb](https://github.com/niemeyer/godeb).
Under certain circumstances, this exception permits distribution of binaries
that are (statically or dynamically) linked with this code, without requiring
the distribution of Minimal Corresponding Source or Minimal Application Code.
For more details, see: [LICENSE](LICENSE).
