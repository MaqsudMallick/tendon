## Project Structure

- **Build System:** Gradle (Kotlin DSL)
- **Source Code:** `src/main/java`
- **Tests:** `src/test/java`

## Architecture: Modular Monolith

This project follows a **modular monolith architecture** using Spring Boot.

### Module Definition

- Every directory created at the same level as `ApiApplication.java` 
(or any java file that holds the @SpringBootApplication annotation) is treated as 
a **module**.
- Each module represents a distinct bounded context or domain.

### Module Internal Structure

Every module MUST have exactly two top-level directories:

#### `api/` — Public Interface
- Contains publicly accessible components:
  - Controllers / REST endpoints
  - DTOs split into two categories:
    - **HTTP DTOs:** Request records grouped in `*Requests.java`, response records in
      `*Responses.java` (e.g., `OrderRequests.java`, `OrderResponses.java`). Do not create
      separate files for each request/response class. These are used only by controllers for
      the HTTP boundary.
    - **Inter-module DTOs:** Standalone public record classes suffixed with `Dto`
      (e.g., `OrderSummaryDto.java`), placed in the `api/dto/` subdirectory. Used as return
      types by the Module Access File. These represent the module's published contract to
      other modules and must not be reused as HTTP response types or vice versa.
  - Public interfaces exposed by this module
- This is the only entry point for external interaction with the module.

#### `internal/` — Internal Implementation
- Contains all internal logic:
  - Service classes
  - Repository interfaces
  - Entity/model classes
  - Configuration specific to the module
  - Internal utilities/helpers
- All classes in `internal/` MUST be **package-private** (no `public` modifier).
- The **only exception** is a **Module Access File** (e.g., `UserModuleAccess.java`) placed under `internal/`.
  - This file acts as the facade through which:
    - The module's own `api/` layer accesses internal services
    - Other modules interact with this module's internals
  - The Module Access File MUST be `public`.

### Inter-Module Communication Rules

- Modules MUST communicate **only** through Module Access Files.
- Module Access Files MUST return inter-module DTOs, never HTTP request/response classes.
  HTTP DTOs are shaped for external clients and may evolve independently from inter-module
  contracts. Mixing the two couples unrelated consumers and makes both harder to change.
- The `api/` layer of a module may call its own `internal/` services via the Module Access File.
- Direct imports from another module's `internal/` package are **strictly forbidden**.
- Avoid circular dependencies between modules.

### HTTP DTO Organization

Group all request records for a module as nested records inside a single `*Requests.java` file
(e.g., `OrderRequests.java`). Group all response records inside a single `*Responses.java` file
(e.g., `OrderResponses.java`). Do not create separate files for each request/response class.

### Example Module Structure

```
src/main/java/com/example/project/
├── ApiApplication.java
├── users/
│   ├── api/
│   │   ├── UserController.java
│   │   ├── UserRequests.java              <-- nested HTTP request records
│   │   ├── UserResponses.java             <-- nested HTTP response records
│   │   └── dto/
│   │       └── UserSummaryDto.java        <-- inter-module DTO returned by facade
│   └── internal/
│       ├── UserModuleAccess.java          <-- public (facade)
│       ├── UserService.java               <-- package-private
│       ├── UserRepository.java            <-- package-private
│       └── User.java                      <-- package-private
├── orders/
│   ├── api/
│   │   ├── OrderController.java
│   │   ├── OrderRequests.java             <-- nested HTTP request records
│   │   ├── OrderResponses.java            <-- nested HTTP response records
│   │   └── dto/
│   │       └── OrderSummaryDto.java       <-- inter-module DTO returned by facade
│   └── internal/
│       ├── OrderModuleAccess.java         <-- public (facade)
│       ├── OrderService.java              <-- package-private
│       ├── OrderRepository.java           <-- package-private
│       └── Order.java                     <-- package-private
```

## Coding Standards

- Follow standard Java conventions (PascalCase for classes, camelCase for methods/variables)
- Use meaningful variable and method names
- Keep methods focused and concise
- **No inline code comments.** Do not add `//` line comments or `/* */` block comments to Java
  code. Code must be self-documenting through naming. Do not remove any comments already present
  in the codebase. The following are **allowed** exceptions:
  - **`/** */` JavaDoc** on public types and methods.
  - **`#` comments in `.properties` files** and other configuration files.
  - A single **`//` comment explaining *why*** an intentional controller `try/catch` exists, as
    required by the [Exception Handling](#exception-handling-no-trycatch-in-controllers) section.
- **Visibility rules per module:**
  - `api/` classes: `public`
  - `internal/` classes: **package-private** (default visibility)
  - Module Access File: **`public`** (only public class in `internal/`)

### Lombok (required for DTOs, models, and events)

Lombok is a project dependency. **Do not write explicit getters or setters by hand** for any
DTO, request/response class, entity/model, event, or exception data-carrier class. Use Lombok
annotations instead:

- **Mutable entities / models** (JPA `@Entity` classes, `@Embeddable`/nested field types): `@Getter @Setter`
- **Request DTOs** (fields populated by Jackson, read-only to application code): `@Getter`
  - Setters are not required because Jackson deserializes via reflection.
- **Event classes** (immutable, final fields set via constructor): `@Getter` +
  `@RequiredArgsConstructor` (or keep the explicit constructor and only add `@Getter`).
- **Exception classes** carrying structured data (e.g. `AppException`): `@Getter` on the class;
  keep the explicit constructor(s) because they call `super(message)`.
- **Nested static classes** (e.g. `User.BankDetails`, `UserRequests.AddressDto`): annotate each
  nested class with the same Lombok annotations — Lombok does not cascade across nesting.
- When the class needs a no-arg constructor for Jackson/JPA and also has an all-args
  constructor, use `@NoArgsConstructor` (and `@AllArgsConstructor` where appropriate).

If a getter/setter contains custom logic, keep it hand-written and use `lombok.AccessLevel.NONE`
on the relevant field (e.g. `@Getter(AccessLevel.NONE)`) to suppress Lombok's generated version.

### Response DTOs (no `Map<String, Object>` returns)

Service methods and controllers **must not** return `Map<String, Object>` or `ResponseEntity<?>`.
Use typed response DTOs instead — preferably Java `record` types placed in the module's `api/`
package.

- **Every endpoint** must have a named response type so that Swagger/OpenAPI generates accurate
  documentation and the compiler catches field-name mistakes.
- **Service methods** return the response DTO (or the entity/record directly). Never build a
  `LinkedHashMap` by hand to represent a response.
- **Controllers** return `ResponseEntity<SpecificDto>`, not `ResponseEntity<?>`.
- **Use Java records** for simple, immutable responses:
  ```java
  // in api/ package
  public record AuthResponse(String token, User user, boolean signup) {}
  ```
- **Lists** should be typed too: return `List<EventSummaryDto>` not `List<Map<String, Object>>`.

### Enum Validation (`ValidationException.requireOneOf`)

String fields that correspond to a fixed set of allowed values (e.g. status/type columns with a
constrained set of values) **must** be validated using the shared helpers on `ValidationException`:

```java
// Single value — throws if value is non-null and not in the allowed set
ValidationException.requireOneOf(status, VALID_STATUSES, "status");

// Collection — throws if any element is not in the allowed set
ValidationException.requireAllOneOf(roles, VALID_ROLES, "role");
```

- Declare allowed values as `private static final Set<String>` constants at the bottom of the service
  class (e.g. `VALID_STATUSES`, `VALID_AUDIENCE_TYPES`).
- Do **not** write inline `if (!set.contains(val)) throw …` or `if (!"a".equals(x) && !"b".equals(x))`.
  Always use `requireOneOf` / `requireAllOneOf` so the pattern, error format, and null handling are
  consistent across every module.
- Validate **before persisting** — call the check after deserialisation and before `repository.save()`.

### Exception Handling (no try/catch in controllers)

Exception handling is centralized in `GlobalExceptionHandler` (a `@RestControllerAdvice` in
`common/api/`). Controllers **must not** wrap handler bodies in try/catch to return 500 or to
translate service errors into HTTP responses — let exceptions bubble up to the global handler.

- **Services throw typed exceptions.** Never throw `new RuntimeException("magic string")` from
  a service and then string-match on the message in the controller. Throw one of the
  `AppException` subclasses so the global handler maps it to the correct status and error code:
  - `ValidationException` → 400
  - `AuthException` → 401
  - `NotAuthorizedException` → 403
  - `NotFoundException` → 404
  - `ConflictException` → 409
  - `InternalException` → 500
  - `OtpCooldownException` / `RateLimitException` → 429
  - Custom subclasses must extend `AppException`, not
    `RuntimeException`, so they flow through `GlobalExceptionHandler.handleAppException`.
- **All exception classes live in `common/api/`.** `AppException` and every subclass — including
  domain-specific ones — belong under `common` module,
  never in a feature module's `api/` or `internal/` package. This keeps the exception hierarchy
  discoverable in one place and lets any module throw any exception without cross-module imports.
- **Controllers stay thin.** A handler method should call the service and return the result.
  If the service method declares `throws Exception` / `throws IOException`, propagate it with a
  matching `throws` clause on the handler — do not swallow it with a catch.
- **Legitimate exceptions to the rule** — keep try/catch in a controller only where the catch
  block implements real business behavior, not just an error envelope (e.g. a webhook that must
  return `200 OK` on any error to avoid a provider retry storm, or a fail-soft verification path
  that converts any error into a benign result the client can act on). There are currently no
  such cases — When you add a controller, exceptions should bubble up to GlobalExceptionHandler. If you add a legitimate exception to this rule,
  leave a short comment explaining *why* the catch is intentional.

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run the application
./gradlew bootRun

# Clean build artifacts
./gradlew clean
```

## Dependencies

- Manage dependencies in `build.gradle.kts`
- Use stable, well-maintained libraries
- Keep dependencies up to date

## Testing

- Write unit tests for all public methods
- Use descriptive test method names (e.g., `shouldReturnExpectedValue`)
- Aim for high test coverage