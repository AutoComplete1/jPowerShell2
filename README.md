
![](https://img.shields.io/maven-central/v/io.github.autocomplete1/jPowerShell2.svg)  ![](https://img.shields.io/github/license/profesorfalken/jPowerShell.svg)  [![CircleCI](https://circleci.com/gh/AutoComplete1/jPowerShell2/tree/master.svg?style=shield)](https://circleci.com/gh/AutoComplete1/jPowerShell2/tree/master)

## 🧑‍🏫 About

jPowerShell2 is a fork of the original [jPowerShell](https://github.com/profesorfalken/jPowerShell) library. Since the original project has been inactive since 2019, this fork aims to modernize the codebase, improve performance using modern Java features (like Virtual Threads), and add support for PowerShell Core (pwsh).

## 🚀 Key Improvements in jPowerShell2

- **Performance:** Switched to Java Virtual Threads (Project Loom) for lightweight non-blocking I/O.
- **PowerShell Core Support:** Easily toggle between classic Windows PowerShell and cross-platform PowerShell Core (pwsh).
- **Async Execution:** Native support for CompletableFuture to run commands without blocking your main thread.
- **Optimized Buffering:** Increased I/O throughput for large command outputs.

## 📝 Requirements

- **Java 21 or higher**

If you are stuck on an older Java version (like Java 8 or 11), you should use the original jPowerShell or build commit `e93759cc29cce870c62549613bd9adaea8112aa7`. However, for modern applications, Java 21+ is highly recommended.

## 💻 Installation

To install jPowerShell you can add the dependecy to your software project management tool: https://search.maven.org/artifact/io.github.autocomplete1/jPowerShell2/1.0.3/jar

Maven:
 ```
<dependency> 
    <groupId>io.github.autocomplete1</groupId>
    <artifactId>jPowerShell2</artifactId>
    <version>1.1.0</version>
    <scope>compile</scope>
</dependency> 
``` 

Gradle:
```
implementation 'io.github.autocomplete1:jPowerShell2:1.1.0'
```

Instead, you can direct download the JAR file and add it to your classpath.   
https://repo1.maven.org/maven2/io/github/autocomplete1/jPowerShell2/1.0.3/jPowerShell2-1.0.3.jar

## ⚡️ Usage

### Single command execution

Quickest way to execute a command and get the result immediately.

```java
PowerShellResponse response = PowerShell.executeSingleCommand("Get-Process");  
System.out.println("Output: " + response.getCommandOutput());
```  

### Using Persistent Sessions (Recommended)

Reusing a session is significantly faster because it keeps the PowerShell process alive in the background.

```java  
try (PowerShell powerShell = PowerShell.openSession()) {
    PowerShellResponse res1 = powerShell.executeCommand("Get-Date");
    PowerShellResponse res2 = powerShell.executeCommand("Get-Service");  
} catch(PowerShellNotAvailableException ex) {
    logger.error("PowerShell not found on this system", ex);
}
```  
### PowerShell Core (pwsh) Support

You can explicitly tell jPowerShell2 to use PowerShell Core (available on Windows, Linux, and macOS).

```java
// Open a session specifically using 'pwsh'
try (PowerShell powerShell = PowerShell.openSession(true)) {  
    powerShell.executeCommand("Write-Host 'Running on PowerShell Core!'");
}
```

### Asynchronous Execution

Run commands in the background and handle the result when it's ready using `CompletableFuture`.

```java
PowerShell.openSession().executeCommandAsync("Start-Sleep -s 5; Get-Service")
    .thenAccept(response -> {
        System.out.println("Async result: " + response.getCommandOutput());
    });
```

### Fluent API

Chain multiple commands together for cleaner code:

```java
PowerShell.openSession()  
    .executeCommandAndChain("Get-Process", (res -> System.out.println("Processes: " + res.getCommandOutput())))  
    .executeCommandAndChain("Get-Date", (res -> System.out.println("Current Date: " + res.getCommandOutput())))  
    .close();
```

## ⚙️ Configuration

You can configure the session via a `jpowershell.properties` file in your classpath or programmatically:

```java
Map<String, String> config = new HashMap<>();  
config.put("maxWait", "30000");      // Timeout in ms (default: 10000)
config.put("useCore", "true");       // Use 'pwsh' instead of 'powershell'
config.put("waitPause", "5");        // Polling interval in ms

response = powerShell.configuration(config).executeCommand("Get-WmiObject Win32_BIOS");
```

| Property   | Description                                           | Default        |
|------------|-------------------------------------------------------|----------------|
| waitPause  | The pause in ms between pooling loops for a response. | 5              |
| maxWait    | The maximum wait in ms for a command to finish.       | 10000          |
| useCore    | Whether to use PowerShell Core (pwsh).                | false          |
| tempFolder | Custom folder for temporary script files.             | java.io.tmpdir |

## 📜 Executing Scripts
To execute a `.ps1` file:

```java
try (PowerShell powerShell = PowerShell.openSession()) {         
    response = powerShell.executeScript("./MyScript.ps1", "-Param1 Value1");  
    System.out.println("Script output: " + response.getCommandOutput());  
}
```

**Scripts inside a JAR**

To run a script bundled as a resource in your JAR file:

```java
BufferedReader reader = new BufferedReader(
    new InputStreamReader(getClass().getResourceAsStream("/scripts/MyScript.ps1")));

try (PowerShell ps = PowerShell.openSession()) {
    PowerShellResponse response = ps.executeScript(reader);
}
```