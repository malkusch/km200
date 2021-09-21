# KM200 Java API

This is a Java API for Bosch/Buderus/Junkers heaters with a KM200 gateway.

# Usage

## TODO Maven

## Configuration

To use the API you'll need the uri, gateway password, private password and the salt.

### Private password

The private password is the one you configure yourself when you connect via the
[app](https://play.google.com/store/apps/details?id=com.bosch.tt.buderus) to your heater.
If you forgot your password you can start the "reset internet password" flow in the menu
of your heater.

### Gateway password

The gateway password is constant and needs to be read out from the menu of your heater (information/internet/login data).

### Salt

I didn't include the salt into this project, because I remember slightly reading somewhere else that Bosch might step in
legally to prevent the publication. So you will have to find the salt on your on. The format is the hexadecimal representation (e.g. "12a0b2…"). Search the internet for KM200 and you'll find projects like these where you could get the salt from:

- [ioBroker.km200](https://github.com/frankjoke/ioBroker.km200/blob/6c0963d671b50cb73f378049448a42cf22a8fecf/km200.js#L13-L17)
- [bosch-thermostat-http-client-python](https://github.com/moustic999/bosch-thermostat-http-client-python/blob/53b2469988c7b25688501669df0981f03a2cbcfa/bosch_thermostat_http/const.py#L5)
- [IPSymconBuderusKM200](https://github.com/demel42/IPSymconBuderusKM200/blob/a71ecedccf8781b607d47692e6c6ebc22a9d1aa3/BuderusKM200/module.php#L683-L686)

## Example

    var uri = "http://192.168.0.44";
    var gatewayPassword = "1234-5678-90ab-cdef";
    var privatePassword = "secretExample";
    var timeout = Duration.ofSeconds(5);
    var salt = "1234567890aabbccddeeff11223344556677889900aabbccddeeffa0a1a2b2d3";
    km200 = new KM200(uri, timeout, gatewayPassword, privatePassword, salt);
    
    // Read the heater's time
    var time = km200.queryString("/gateway/DateTime");
    System.out.println(time);
    
    // Update the heater's time
    km200.update("/gateway/DateTime", LocalDateTime.now());

# License

The encryption and decryption was extracted from the OpenHAB project which itself is under the 
Eclipse Public License 2.0. For simplicity I chose to use the same license for this project.
