package com.ragpeat;

import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagPeatApplication {

	/** Comandos de terminal (download, index...). Con ellos no hace falta arrancar el servidor web. */
	private static final Set<String> COMMANDS = Set.of("download", "index", "index-interviews", "embed", "search", "ask", "eval", "compare", "stats", "redact", "topics");

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(RagPeatApplication.class);
		// Sin esto, al lanzar un comando también arrancaría el servidor web, y el programa
		// se quedaría abierto esperando peticiones en vez de terminar.
		if (args.length > 0 && COMMANDS.contains(args[0])) {
			app.setWebApplicationType(WebApplicationType.NONE);
		}
		app.run(args);
	}

}
