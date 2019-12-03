package laptyuhov.safetemplates.processor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import laptyuhov.safetemplates.SafeTemplate;

@SupportedAnnotationTypes("laptyuhov.safetemplates.SafeTemplate")
@SupportedSourceVersion(SourceVersion.RELEASE_12)
public class SafeTemplateProcessor extends AbstractProcessor {

  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.isEmpty() || roundEnv.processingOver()) {
      return false;
    }

    HashMap<TypeElement, List<ExecutableElement>> classesToGenerate = new HashMap<>();
    for (Element element : roundEnv.getElementsAnnotatedWith(SafeTemplate.class)) {
      checkCondition(
          element.getKind().equals(ElementKind.METHOD),
          String.format("Expected element kind for @SafeTemplate: %s. Actual: %s.",
              ElementKind.METHOD,
              element.getKind()));
      ExecutableElement method = (ExecutableElement) element;

      checkCondition(
          method.getEnclosingElement().getKind().equals(ElementKind.INTERFACE),
          "Interface is expected!");//TODO(vlaptyuhov) better error message
      TypeElement interfac = (TypeElement) method.getEnclosingElement();

      checkCondition(
          processingEnv.getTypeUtils().isSameType(
              method.getReturnType(),
              processingEnv.getElementUtils().getTypeElement("java.lang.String").asType()),
          String.format("Expected method return type: String. Actual: %s. Interface: %s. Method: %s.",
              method.getReturnType().toString(),
              interfac.getQualifiedName(),
              method.getSimpleName()));

      for (VariableElement parameter : method.getParameters()) {
        checkCondition(
            processingEnv.getTypeUtils().isSameType(
                parameter.asType(),
                processingEnv.getElementUtils().getTypeElement("java.lang.String").asType()),
            String.format("Expected parameter type: String. Actual: %s. Interface: %s. Method: %s.",
                parameter.asType(),
                interfac.getQualifiedName(),
                method.getSimpleName()));
      }

      classesToGenerate.putIfAbsent(interfac, new ArrayList<>());
      classesToGenerate.get(interfac).add(method);
    }

    for (Map.Entry<TypeElement, List<ExecutableElement>> entry
        : classesToGenerate.entrySet()) {
      TypeElement interfac = entry.getKey();
      List<ExecutableElement> methods = entry.getValue();

      String generatedClassName = "$" + interfac.getSimpleName();
      try {
        JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(
            generatedClassName);
        try (Writer w = sourceFile.openWriter()) {
          String packageOrEmptyString =
              processingEnv.getElementUtils().getPackageOf(interfac).getQualifiedName().toString();
          if (!packageOrEmptyString.isEmpty()) {
            w.write(String.format("package %s;\n", packageOrEmptyString));
            w.write("\n");
          }

          w.write(String.format(
              "public final class %s implements %s {\n",
              generatedClassName,
              interfac.getSimpleName()));
          w.write("\n");

          for (ExecutableElement method : methods) {
            w.write("  @Override\n");

            if (method.getParameters().isEmpty()) {
              w.write(String.format("  public String %s() {\n", method.getSimpleName()));
            } else {
              StringBuilder parametersBuilder = new StringBuilder();
              for (VariableElement parameter : method.getParameters()) {
                parametersBuilder.append("String").append(" ")
                    .append(parameter.getSimpleName().toString()).append(", ");
              }
              parametersBuilder.delete(
                  parametersBuilder.length() - ", ".length(), parametersBuilder.length());
              w.write(String.format(
                  "  public String %s(%s) {\n",
                  method.getSimpleName(),
                  parametersBuilder.toString()));
            }

            for (VariableElement parameter : method.getParameters()) {
              w.write(
                  String.format("    if (%s == null) {\n", parameter.getSimpleName().toString()));
              w.write(String.format(
                  "        throw new NullPointerException(\"'%s' can't be null\");\n",
                  parameter.getSimpleName().toString()));
              w.write("    }\n");
            }

            String templatePath = method.getAnnotation(SafeTemplate.class).value();

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(templatePath)));
            String templateContent = "";
            String line;
            while ((line = reader.readLine()) != null) {
              templateContent += line;
            }

            w.write(String.format("    return \"%s\";\n", templateContent));

            w.write("  }\n");
            w.write("\n");
          }

          w.write("}\n");
        }
      } catch (IOException e) {
        processingEnv.getMessager().printMessage(
            Kind.ERROR,
            "Failed to create source file: " + generatedClassName);
      }
    }

    return false;
  }

  private void checkCondition(boolean condition, String errorMessage) {
    if (!condition) {
      processingEnv.getMessager().printMessage(Kind.ERROR, errorMessage);
    }
  }
}
