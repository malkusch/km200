# KM200 Java API

This is a Java API for heaters with a KM200 gateway.
Heaters of theses brands may use this gateway (according to `/system/brand`):
Bosch, Junkers, Buderus, Nefit, Sieger, Tata, Dakon, Elm, Boulter, Vulcano, Worcester, British Gas, IVT, Geminox, Neckar, Zeus, Milton

## Maven

This package is available in Maven central:
```xml maven
<dependency>
  <groupId>de.malkusch.km200</groupId>
  <artifactId>km200</artifactId>
  <version>3.0.3</version>
</dependency>
```

### Thread safety

Code wise this API is thread safe, it is highly recommended to not
use it concurrently. Your KM200 gateway itself is not thread safe. In order
to protect users from wrong usage, this API will serialize all requests, i.e.
concurrent requests will happen sequentially.

## License

The encryption and decryption was extracted from the [OpenHAB](https://github.com/openhab/openhab1-addons/tree/v1.10.0/bundles/binding/org.openhab.binding.km200/src/main/java/org/openhab/binding/km200/internal) project which itself is under the 
Eclipse Public License 2.0. For simplicity I chose to use the same license for this project.
