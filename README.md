# Immersive Boats

Immersive Boats añade estelas de espuma, sonidos de remada y un cabeceo suave a las barcas de Hytale **0.8.0-pre.6**.

## Compilar

Instala JDK 25 y Maven 3.9 o posterior. En IntelliJ IDEA, importa el proyecto desde `pom.xml` y selecciona JDK 25.

```powershell
mvn clean verify
```

El JAR queda en `target/immersive-boats-0.0.1.jar`. Maven resuelve la API desde el repositorio pre-release de Hytale. El servidor aporta la API al cargar el plugin.

## Probar en local

Instala la pre-release compatible en el launcher. Desde PowerShell:

| Comando | Acción |
| --- | --- |
| `./run-server.ps1 -DeployOnly` | Compila y copia el plugin a `.hytale-server/mods/immersive-boats.jar`. |
| `./run-server.ps1` | Compila, despliega y arranca el servidor en `127.0.0.1:5520`. |
| `./run-server.ps1 -DebugServer` | Inicia la JVM y espera a que se conecte el depurador Java en `127.0.0.1:5005`. |

En IntelliJ IDEA, ejecuta **Run Hytale Server** para arrancar normalmente. Para depurar desde el inicio del plugin, detén cualquier instancia previa, ejecuta **Start Hytale for Debugging** con **Run** y después inicia **Attach to Hytale Server** con **Debug**. El servidor continúa el arranque cuando se conecta IntelliJ.

Usa `-GameDirectory` para indicar otra instalación que contenga `Server/HytaleServer.jar` y `Assets.zip`. Usa una instalación de la versión declarada en `hytale.version` en el POM. Para desplegar en otro servidor, copia el JAR de `target/` a su carpeta `mods/`.

Si actualizas desde Demo, sustituye su JAR por el de Immersive Boats y conserva la carpeta `org.example_demo`. El mod lee las preferencias de esa carpeta hasta que se guarden con el nombre nuevo.

Para comprobar la estela, conduce una barca de madera por el agua. Al girar en el sitio aparece espuma a ambos lados. Si la barca se desplaza de lado, aparece espuma en el costado hacia el que se mueve. Cuando la barca queda quieta o desembarcas, la espuma se disipa.

Al avanzar o girar en el agua se oye un chapoteo suave que alterna entre los dos costados. Cuando la barca se detiene, cesan las remadas.

Usa `/rowing_volume` para consultar tu volumen o `/rowing_volume 50` para ajustarlo al 50 %. El 100 % es el volumen original y el 0 % silencia las remadas solo para ti. El servidor recuerda tu preferencia al volver a conectarte. El comando muestra sus mensajes y la ayuda en el idioma del cliente, con inglés como respaldo.

Usa `/rowing_opacity` para consultar la opacidad o `/rowing_opacity 50` para ver la estela y la espuma de las remadas al 50 % de su opacidad original. El 0 % oculta la espuma solo para ti y el 100 % recupera el efecto original. El servidor guarda este ajuste por jugador.

## Empezar a desarrollar

- `src/main/java/org/immersiveboats/ImmersiveBoatsPlugin.java` registra el sistema de estela en `setup()`.
- `src/main/resources/manifest.json` declara la clase principal y los metadatos que lee Hytale. Maven toma del POM el grupo, el nombre visible, la descripción y las versiones del mod y del servidor.
- Para añadir un idioma, copia `Server/Languages/en-US/immersive_boats.lang` a la carpeta del idioma correspondiente dentro de `src/main/resources/Server/Languages/`. Conserva las claves y los parámetros como `{percent, number}` y `{example}`.
