package tests.app;

import app.StartupPaths;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Папки запуска для исходников, exe и явного app.data. Свойства и окружение подменяются картами. */
public final class StartupPathsTest {
    private static int checks;

    public static void main(String[] args) {
        Map<String, String> local = Map.of("LOCALAPPDATA", "C:/Users/u/AppData/Local");

        StartupPaths sources = resolve(Map.of("app.root", "C:/project"), local);
        check(sources.root().equals(Path.of("C:/project")) && sources.data().equals(Path.of("C:/project"))
                && !sources.packaged() && !sources.managedData(), "Sources keep data in the project and never create a database");
        StartupPaths explicitSources = resolve(Map.of("app.root", "C:/project", "app.data", " D:/data "), local);
        check(explicitSources.data().equals(Path.of("D:/data")) && !explicitSources.managedData(), "app.data overrides the project folder");
        check(resolve(Map.of(), local).root().equals(Path.of("").toAbsolutePath()), "Root defaults to the working directory");

        Map<String, String> exe = new HashMap<>(Map.of("app.root", "C:/apps/TestApp",
                "jpackage.app-path", "C:/apps/TestApp/TestApp.exe", "user.home", "C:/Users/u"));
        StartupPaths packaged = resolve(exe, local);
        check(packaged.root().equals(Path.of("C:/apps/TestApp"))
                && packaged.data().equals(Path.of("C:/Users/u/AppData/Local/TestApp"))
                && packaged.packaged() && packaged.managedData(), "Exe keeps data in LOCALAPPDATA");
        check(resolve(exe, Map.of("LOCALAPPDATA", "  ")).data().equals(Path.of("C:/Users/u/AppData/Local/TestApp")),
                "Blank LOCALAPPDATA falls back to the user profile");
        check(resolve(exe, Map.of("LOCALAPPDATA", "Local")).data().equals(Path.of("C:/Users/u/AppData/Local/TestApp")),
                "Relative LOCALAPPDATA falls back to the user profile");
        check(resolve(exe, Map.of()).data().equals(Path.of("C:/Users/u/AppData/Local/TestApp")),
                "Missing LOCALAPPDATA falls back to the user profile");

        exe.put("jpackage.app-path", "C:/apps/Notes/Notes.EXE");
        check(resolve(exe, local).data().equals(Path.of("C:/Users/u/AppData/Local/Notes")), "Data folder follows the exe name");

        exe.put("app.data", "D:/portable");
        StartupPaths explicitExe = resolve(exe, local);
        check(explicitExe.data().equals(Path.of("D:/portable")) && explicitExe.packaged() && !explicitExe.managedData(),
                "Explicit app.data in the exe is not auto-created");
        System.out.println("Startup path checks passed: " + checks);
    }

    private static StartupPaths resolve(Map<String, String> properties, Map<String, String> environment) {
        return StartupPaths.resolve(properties::get, environment::get);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) { throw new AssertionError(message); }
    }
}
