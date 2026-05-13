# Build Module

Convenience helper for building one or more Oak modules with the right Maven flags. Helps avoid full-tree rebuilds.

## Usage

- `/build-module <module>` — Build a single module (skip tests). Example: `/build-module oak-core`
- `/build-module <module> --with-deps` — Build the module and its upstream dependencies. Example: `/build-module oak-core --with-deps`
- `/build-module <module> --with-dependents` — Build the module and all modules that depend on it (use after SPI/API changes). Example: `/build-module oak-store-spi --with-dependents`
- `/build-module <module> --test` — Run the test suite for the module after building.

## Mapping to Maven

| Form | Maven Command |
|------|---------------|
| `<module>` | `mvn clean install -pl <module> -DskipTests` |
| `<module> --with-deps` | `mvn clean install -pl <module> -am -DskipTests` |
| `<module> --with-dependents` | `mvn clean install -pl <module> -amd -DskipTests` |
| `<module> --test` | `mvn test -pl <module>` |

For fast iteration without tests, you can also use `mvn clean install -Pfast` (no tests, no coverage).

## Examples

```bash
# Build just oak-core
/build-module oak-core

# Build oak-store-document and everything it needs
/build-module oak-store-document --with-deps

# Rebuild everything that depends on oak-security-spi after an SPI change
/build-module oak-security-spi --with-dependents

# Run oak-jcr's test suite
/build-module oak-jcr --test
```

## Notes

- Always rebuild dependents (`-amd`) after changing an `*-spi` or `*-api` module.
- For fixture tests, you may also need `-PintegrationTesting` (see `AGENTS.md` "Test Commands").
- For the `DOCUMENT_NS` fixture, MongoDB must be running on `localhost:27017`.
