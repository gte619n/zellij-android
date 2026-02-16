package com.zellijconnect.app;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(
    include = {
        "java", "kotlin", "python", "javascript", "json", "yaml",
        "markup", "css", "clike", "c", "cpp", "go",
        "groovy", "sql", "scala", "swift", "dart",
        "csharp", "markdown", "makefile", "git", "latex"
    },
    grammarLocatorClassName = ".PrismGrammarLocator"
)
public class PrismLanguages {
}
