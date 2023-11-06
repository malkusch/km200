# KM200 Java API

This is a Java API for heaters with a KM200 gateway.
Heaters of theses brands may use this gateway (according to `/system/brand`):
Bosch, Junkers, Buderus, Nefit, Sieger, Tata, Dakon, Elm, Boulter, Vulcano, Worcester, British Gas, IVT, Geminox, Neckar, Zeus, Milton

## Maven

This package is available in Maven central:
```xml
<dependency>
    <groupId>de.malkusch.km200</groupId>
    <artifactId>km200</artifactId>
    <version>2.1.0</version>
</dependency>
```

## Configuration

To use the API you'll need the uri, gateway password, private password and the salt.

### Private password

The private password is the one you configure yourself when you connect via the
[app](https://play.google.com/store/apps/details?id=com.bosch.tt.buderus) to your heater.
If you forgot your password you can start the "reset internet password" flow in the menu
of your heater and then reassign a new password in the app.

### Gateway password

The gateway password is constant and needs to be read out from the menu of your heater (information/internet/login data).

### Salt

I didn't include the salt, because I remember slightly reading somewhere else that Bosch might step in
legally to prevent the publication. So you will have to find the salt on your own, either by searching the internet
or decompiling the [app](https://play.google.com/store/apps/details?id=com.bosch.tt.buderus).
The format is the hexadecimal representation (e.g. `12a0b2…`). Here's an incomplete list of projects in the internet
where you could find the salt:

- [ioBroker.km200](https://github.com/frankjoke/ioBroker.km200/blob/6c0963d671b50cb73f378049448a42cf22a8fecf/km200.js#L13-L17)
- [bosch-thermostat-http-client-python](https://github.com/moustic999/bosch-thermostat-http-client-python/blob/53b2469988c7b25688501669df0981f03a2cbcfa/bosch_thermostat_http/const.py#L5)
- [IPSymconBuderusKM200](https://github.com/demel42/IPSymconBuderusKM200/blob/a71ecedccf8781b607d47692e6c6ebc22a9d1aa3/BuderusKM200/module.php#L683-L686)
- [km200exporter](https://github.com/dirklausen/km200exporter/blob/976344b8f1bec476f25ca1e5619faff12fdccd1d/km200exporter.py#L20)

Additionally I found [this project](https://github.com/bosch-thermostat/bosch-thermostat-client-python/tree/b82b6c46468a647ddf1a2cada38146db8e5ff14f/bosch_thermostat_client/const) which seems to use different salts, depending on the heater's brand.

## Usage

With this library you can access well known endpoints of your KM200 (e.g. `/gateway/DateTime` for your heater's time).
The list of endpoints varies between installations, therefore you have to explore your KM200 yourself. Some endpoints
are writeable (e.g. `/gateway/DateTime`) which you can update with this library as well.

### Examples

Setup a KM200 instance:

```java
var uri = "http://192.168.0.44";
var gatewayPassword = "1234-5678-90ab-cdef";
var privatePassword = "secretExample";
var timeout = Duration.ofSeconds(5);
var salt = "1234567890aabbccddeeff11223344556677889900aabbccddeeffa0a1a2b2d3";

var km200 = new KM200(uri, timeout, gatewayPassword, privatePassword, salt);
```

Read and update the heater's time:

```java
// Read the heater's time
var time = km200.queryString("/gateway/DateTime");
System.out.println(time);

// Update the heater's time
km200.update("/gateway/DateTime", LocalDateTime.now());
```

Explore all endpoints:

```java
km200.endpoints().forEach(System.out::println);
```

### Thread safety

Code wise this API is thread safe, it is highly recommended to not
use it concurrently. Your KM200 gateway itself is not thread safe. In order
to protect users from wrong usage, this API will serialize all requests, i.e.
concurrent requests will not happen concurrently.

## License

The encryption and decryption was extracted from the [OpenHAB](https://github.com/openhab/openhab1-addons/tree/v1.10.0/bundles/binding/org.openhab.binding.km200/src/main/java/org/openhab/binding/km200/internal) project which itself is under the 
Eclipse Public License 2.0. For simplicity I chose to use the same license for this project.
