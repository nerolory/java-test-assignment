# Test App

JavaFX application. Java packages use lowercase names; classes use PascalCase.

The first working database core is documented in [docs/database-core.md](docs/database-core.md),
including its architecture, examples, limits and integration checks. Its SQLite
configuration is in `config/database.properties`; the concrete model is `app/models/User.java`.
Install JDBC with `scripts/install-jdbc.ps1` before the first Java build.

```text
app/
  Main.java                     Application entry point (app.Main)
  controllers/                  FXML event handlers and UI coordination
    AuthenticationController.java
    RegisterController.java
    WelcomeController.java
    HeaderController.java
  contracts/
    Controllable.java           initialize() lifecycle interface
  services/                     Business logic
  repositories/                 Data access interfaces and implementations
resources/
  fxml/
    pages/login.fxml            Login screen
    components/header.fxml      Shared header
  assets/
    css/app.css                 Generated shared styles
    scss/                       Style sources
    fonts/                      Local fonts and license
    images/                     Images and icons
.deps/                          Local SDKs and installers (ignored by Git)
bin/                            Build output (ignored by Git)
```

## First-time setup

`.deps/`, `bin/` and `database/*.db` are not kept in Git, so a fresh checkout needs:

1. JDK 26.0.1 in `C:/Program Files/Java/jdk-26.0.1` (the path is fixed in `run.ps1`,
   `scripts/*.ps1` and `.vscode/settings.json`).
2. JavaFX SDK 26.0.2 for Windows from [Gluon](https://gluonhq.com/products/javafx/),
   unpacked so that `.deps/javafx-sdk-26.0.2/lib` exists.
3. Node.js, then `npm.cmd ci` for the style compiler.
4. `scripts/install-jdbc.ps1` — downloads SQLite JDBC and verifies its checksum.
5. `scripts/init-database.ps1` — creates `database/app.db` from `database/schema.sql`.
   It refuses to overwrite an existing database; update one with `scripts/migrate-database.ps1`.

## Run

Install the style compiler once with `npm.cmd ci` (Node.js required).

`powershell -ExecutionPolicy Bypass -File ./run.ps1`

Compile only: `./run.ps1 -CompileOnly`. Each build compiles into a temporary folder and
mirrors it into `bin/` with robocopy: classes and resources removed from the sources
disappear from the classpath, while unchanged files stay in place, so a running app or
a parallel test script never sees `bin/` missing.
Cursor: Run Code or the **Run Main (JavaFX)** debug configuration.
The build copies everything inside `resources/` to the classpath root.
Load screens using absolute paths such as `/fxml/pages/login.fxml`.

## Packaging (exe)

`powershell -ExecutionPolicy Bypass -File ./scripts/package.ps1` builds
`dist/TestApp/TestApp.exe` with jpackage from the JDK. The folder (about 100 MB)
contains its own Java runtime, so the target computer needs no Java or JavaFX;
copy or zip the whole `dist/TestApp` folder. The runtime's JDK modules are computed
with jdeps from the app, SQLite JDBC and JavaFX. Add `-Console` for a build that also
shows a console window.

The script compiles into its own folder (`run.ps1 -OutputDir`), because the IDE's Java
builder also writes tests and scripts into `bin/`. It builds the new image under
`.deps/package` and replaces `dist/TestApp` only after jpackage succeeds, and it stops
with a message if `TestApp.exe` from that folder is still running.

```text
dist/TestApp/
  TestApp.exe                   Launcher
  app/                          app.jar, SQLite JDBC, launcher settings (TestApp.cfg)
  runtime/                      Java runtime from jlink (only the required JDK modules)
  javafx/                       JavaFX jars and native DLLs (no WebView or media)
  config/, database/schema.sql  Read-only defaults
```

The exe keeps writable data in `%LOCALAPPDATA%\<exe name>` (`app.StartupPaths`):

- `database/app.db` is created from `schema.sql` on the first launch. The schema is
  applied in a temporary file that is then renamed, so an interrupted first launch
  leaves no database without tables, and a second instance started at the same time
  uses the database the first one created. Temporary files of interrupted attempts
  older than a minute are removed on the next launch.
- `.local/auth-session.bin` holds the remembered session.
- `logs/` (`app0.log`, 3 files of 1 MB; a second running instance gets a suffixed file)
  receives the app's log, since the exe has no console. If startup fails, including with
  a JVM `Error` such as `NoClassDefFoundError`, a dialog shows the reason and the log
  folder instead of the exe silently closing.

Data stays there when the exe folder is replaced. When started through `run.ps1`, the
app keeps using the project folder, logs to the console and never creates the database
itself. `-Dapp.data=<folder>` overrides the data folder in both modes (for the exe,
through the `JAVA_TOOL_OPTIONS` environment variable). An explicit folder must already
exist and is never written to before that check, so a typo neither starts the app with
an empty database nor leaves a stray folder with logs. Create its database with
`scripts/init-database.ps1 -DatabasePath <folder>\database\app.db`.

An installer (`jpackage --type exe` or `msi`) additionally needs the WiX Toolset.
The packaged app does not run migrations: after a schema change, update an existing
`%LOCALAPPDATA%\TestApp\database\app.db` with
`scripts/migrate-database.ps1 -DatabasePath <path>`.

## Scene Builder

Use **Tasks: Run Task → Scene Builder: Open login.fxml** or
**Scene Builder: Open current FXML**. Edit the shared header by opening
`resources/fxml/components/header.fxml`.

## Reusable header

Include the header in a screen:

```xml
<fx:include fx:id="header" source="../components/header.fxml" />
```

The screen controller receives the included controller through its include ID
plus the `Controller` suffix:

```java
@FXML
private HeaderController headerController;

@FXML
void initialize() {
    headerController.setTitle("MY PAGE");
    headerController.setActionButton(headerButton);
}
```

The title is applied at runtime; Scene Builder shows the header's default text.
Each include creates a separate header instance with the same shared layout.
The header has an empty action container. A page declares its optional button
inside `fx:define`, injects it with `@FXML`, then passes it to
`headerController.setActionButton(...)` from `initialize()`. The login page
supplies REGISTER, registration supplies LOGIN, and welcome supplies no button.
Use `setActionButton(null)` to clear the action. Button captions and styles stay
in the page FXML; event handlers belong to the page controller. Scene Builder
shows the empty slot because it does not run the page controller.

## Fields and buttons

Standard TextField, PasswordField and Button controls are declared directly in
page FXML (or in the header for its own button). Prompt text, button captions,
fx:id and placement belong to FXML; shared appearance comes from SCSS classes.
Only composite blocks such as the header use fx:include.

```xml
<TextField fx:id="loginField" promptText="Login"
           styleClass="o-control, field" />
<Button fx:id="submitButton" text="Login"
        styleClass="o-control, app-button, app-button--primary" />
```

Page controllers receive controls via @FXML and assign event handlers.
All controllers implement Controllable and implement public initialize().
## Application layers

Controller dependencies are registered in `app.providers.AppServiceProvider`.
The registration mechanism lives in `core.container.ServiceContainer`, and the
provider contract in `core.contracts.ServiceProvider`. `Main` creates a container,
registers `AppServiceProvider`. `WindowController` (the `Navigator` implementation)
configures each loader with the container's `resolve` method as its controller factory.
FXML retains only `fx:controller`; services are not declared in markup.
The container creates a new controller for every load,
including controllers in `fx:include`. The authentication service is shared
by the controllers created from that registration.

`AuthenticationController` receives the `app.contracts.AuthenticationService`
interface through its constructor. `DefaultAuthenticationService` accepts a
`LoginRequest`, validates it, verifies the password and creates a persistent session.
Controllers display a typed `AuthenticationResult`; database and password work runs
on the bounded background executor, outside the JavaFX thread.
Any new loader that opens the login page must use the configured container's
`resolve` method as its controller factory. Core has no dependency on JavaFX or app.

Controllers → service contracts → authentication repository → database core.

Controllers handle JavaFX controls, navigation and user input. Services contain
business rules. Authentication has a repository contract for credential and session
queries; it does not expose password hashes through the public user model.
Active Record (`User` and `core.database.Model`) remains available for model operations.
Core and models do not depend on JavaFX. Registration uses its own service and
repository contracts, validates all four fields, hashes passwords and handles
unique-constraint conflicts even when concurrent requests pass the initial checks.
After registration, the form offers a return to login.

Login, attempt limits, password format, remembered sessions and tests are described in
[docs/authentication.md](docs/authentication.md).
Registration rules and tests are described in [docs/registration.md](docs/registration.md).
Controllers log infrastructure failures through `System.Logger` (console by default);
the user sees only a generic message.

This has a Laravel-like separation of responsibilities, with Java packages
instead of PHP namespaces and FXML instead of Blade templates.

The core now checks record versions, joins model calls to the active transaction,
bounds queries and concurrent operations, and supports batch inserts and cancellation.
See [docs/database-core.md](docs/database-core.md) for API examples and limits, and
[docs/performance.md](docs/performance.md) for the local load probe.
Run `scripts/migrate-database.ps1` once for an existing database before using models.

## SCSS, OOCSS and BEM

Edit `resources/assets/scss/`, not the generated `resources/assets/css/app.css`.
The build emits compressed CSS without source maps. `scripts/build-styles.cjs`
preserves the trailing semicolon in `@font-face`, required by JavaFX's parser.
JavaFX reads CSS, not SCSS. Dart Sass compiles SCSS into JavaFX-compatible
`-fx-*` declarations. Browser CSS layout, CSS variables and web fonts such as
WOFF are not used.

```text
resources/assets/scss/
  app.scss                  Entry point, module order
  settings/_tokens.scss     Colors, font sizes, radius, font family
  base/_fonts.scss          Local font declarations
  objects/_control.scss     Shared control geometry (.o-control)
  components/_field.scss    Field appearance and focus state
  components/_button.scss   Button appearance and states
  components/_header.scss   Shared header block
  pages/_auth.scss          Authentication page block
resources/assets/fonts/
  NotoSans-Regular.ttf
  NotoSans-Bold.ttf
  OFL.txt                   Font license
```

OOCSS separates geometry (`o-control`) from skins (`field`, `app-button`).
Controls do not depend on their parent's selectors. BEM uses block names
(`app-header`), elements (`app-header__title`) and modifiers
(`app-button--primary`). Flat selectors avoid specificity escalation; interaction
states use JavaFX pseudo-classes such as `:hover`, `:pressed` and `:focused`.
Placement and container dimensions stay in FXML; visual styling lives in SCSS.

For example, a button's classes are:

```xml
styleClass="o-control, app-button, app-button--primary"
```

Each component and page references the generated stylesheet, so they can also
be opened separately in Scene Builder. Included components have no new
controllers. Registration validation and submission are handled by the page controller
and its injected service; Scene Builder previews the static layout.

Commands:

- `npm.cmd run styles:build` — compile once.
- `npm.cmd run styles:watch` — rebuild CSS after SCSS changes; keep running while
  editing in Scene Builder and refresh/reopen its preview when necessary.
- `./run.ps1` — compile SCSS, compile Java, copy runtime resources and launch.

Cursor tasks **Styles: Build SCSS** and **Styles: Watch SCSS** run these commands.
Keep `app.css` in version control so Scene Builder works before Node setup.
SCSS sources are excluded from runtime resource copying.

Noto Sans includes Cyrillic and is bundled locally, with no system installation
or network access needed at runtime. Source: https://github.com/notofonts/noto-fonts
