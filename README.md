# WestGate
> Pedo Mellon a Minno.

WestGate is a HTTP connection router based on HTTP headers. WestGate tests the 
headers of the first HTTP request for each connection and forward to the 
configured server. If a connection does not match any server or it's not a valid
HTTP connection, WestGate will route the connection to the default server. Any 
HTTP header can be tested like "Host", "Authorization", "User-Agent", etc.

![WestGate Diagram](https://raw.githubusercontent.com/tetrau/westgate/master/doc/img/WestGate%20Diagram.png)

# Quick Start

**Step1:** Create a json config file

```json
{
  "bind": {
    "host": "127.0.0.1",
    "port": 8765
  },
  "default": {
    "host": "127.0.0.1",
    "port": 80
  },
  "forward": [
    {
      "headerField": "Authorization",
      "headerValue": "Basic dXNlcjpwYXNzd29yZA==",
      "host": "127.0.0.1",
      "port": 8080
    }
  ]
}
```
It's pretty self-explanatory, `bind` sets which interface and port should 
WestGate listen on. `default` is the default server all unmatched and invalid
connection will goto. And `forward` lists all routing rules.

**Step2:** Start WestGate

Download WestGate jar from [release](https://github.com/tetrau/westgate/releases).

```bash
java -jar westgate.jar config.json
```
