# Applitools Jenkins Plugin

Early scaffold. Today it adds an "Applitools Eyes" build step that only logs its inputs; the real behaviour is still to be designed.

## Develop

```bash
mvn hpi:run            # Jenkins with the plugin loaded at http://localhost:8080/jenkins
mvn verify             # build target/applitools-jenkins.hpi and run tests
```

## Layout

| Path | Role |
| --- | --- |
| `pom.xml` | Jenkins plugin parent POM, Jenkins baseline, plugin BOM |
| `ApplitoolsBuilder.java` | Build step: Freestyle UI + Pipeline `applitools appName: '...'` |
| `ApplitoolsGlobalConfiguration.java` | Manage Jenkins → System settings (Eyes server URL) |
| `src/main/resources/.../*.jelly` | Config forms; `help-*.html` are the (?) tooltips |
| `src/test/...` | `JenkinsRule` tests that boot a real Jenkins |
