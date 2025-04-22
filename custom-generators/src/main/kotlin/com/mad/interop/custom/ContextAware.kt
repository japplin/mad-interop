package com.mad.interop.custom

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import javax.inject.Inject
import kotlin.reflect.KClass

@Suppress("TooManyFunctions")
internal interface ContextAware {
  val logger: KSPLogger

  private val anyFqName
    get() = Any::class.requireQualifiedName()

  val injectFqName
    get() = Inject::class.requireQualifiedName()

  val scopedFqName
    get() = "com.mad.interop.scopes.Scoped"

  val scopedClassName
    get() = ClassName("com.mad.interop.scopes", "Scoped")

  fun <T : Any> requireNotNull(value: T?, symbol: KSNode?, lazyMessage: () -> String): T {
    if (value == null) {
      val message = lazyMessage()
      logger.error(message, symbol)
      throw IllegalArgumentException(message)
    }

    return value
  }

  fun check(condition: Boolean, symbol: KSNode?, lazyMessage: () -> String) {
    if (!condition) {
      val message = lazyMessage()
      logger.error(message, symbol)
      throw IllegalStateException(message)
    }
  }

  fun checkIsPublic(clazz: KSClassDeclaration) {
    check(clazz.getVisibility() == Visibility.PUBLIC, clazz) {
      "Contributed component interfaces must be public."
    }
  }

  fun checkHasScope(clazz: KSClassDeclaration) {
    // Ensures that the value is non-null.
    clazz.scope()
  }

  fun KSClassDeclaration.scope(): MergeScope {
    return requireNotNull(scopeOrNull(), this) { "Couldn't find scope for $this." }
  }

  private fun KSClassDeclaration.scopeOrNull(): MergeScope? {
    val annotationsWithScopeParameter =
      annotations
        .filter { it.hasScopeParameter() }
        .toList()
        .ifEmpty {
          return null
        }

    return scopeForAnnotationsWithScopeParameters(this, annotationsWithScopeParameter)
  }

  private fun KSAnnotation.hasScopeParameter(): Boolean {
    return (annotationType.resolve().declaration as? KSClassDeclaration)
      ?.primaryConstructor
      ?.parameters
      ?.firstOrNull()
      ?.name
      ?.asString() == "scope"
  }

  private fun scopeForAnnotationsWithScopeParameters(
    clazz: KSClassDeclaration,
    annotations: List<KSAnnotation>,
  ): MergeScope {
    val explicitScopes = annotations.map { annotation -> annotation.scopeParameter() }

    explicitScopes.scan(explicitScopes.first().declaration.requireQualifiedName()) { previous, next,
      ->
      check(previous == next.declaration.requireQualifiedName(), clazz) {
        "All scopes on annotations must be the same."
      }
      previous
    }

    return MergeScope(explicitScopes.first())
  }

  private fun KSAnnotation.scopeParameter(): KSType {
    return requireNotNull(scopeParameterOrNull(), this) { "Couldn't find a scope parameter." }
  }

  private fun KSAnnotation.scopeParameterOrNull(): KSType? {
    return arguments.firstOrNull { it.name?.asString() == "scope" }?.let { it.value as? KSType }
  }

  fun KSClassDeclaration.findAnnotation(annotation: KClass<out Annotation>): KSAnnotation =
    findAnnotations(annotation).single()

  fun KSClassDeclaration.findAnnotations(annotation: KClass<out Annotation>): List<KSAnnotation> {
    val fqName = annotation.requireQualifiedName()
    return annotations
      .filter { it.isAnnotation(fqName) }
      .toList()
      .also {
        check(it.isNotEmpty(), this) {
          "Couldn't find the @${annotation.simpleName} annotation for $this."
        }
      }
  }

  fun KSAnnotation.isAnnotation(fqName: String): Boolean {
    return annotationType.resolve().declaration.requireQualifiedName() == fqName
  }

  fun KSDeclaration.requireContainingFile(): KSFile =
    requireNotNull(containingFile, this) { "Containing file was null for $this" }

  fun KSDeclaration.requireQualifiedName(): String =
    requireNotNull(qualifiedName?.asString(), this) { "Qualified name was null for $this" }

  fun KClass<*>.requireQualifiedName(): String =
    requireNotNull(qualifiedName) { "Qualified name was null for $this" }

  fun Resolver.getSymbolsWithAnnotation(annotation: KClass<*>): Sequence<KSAnnotated> =
    getSymbolsWithAnnotation(annotation.requireQualifiedName())

  fun KSDeclaration.innerClassNames(separator: String = ""): String {
    val classNames = requireQualifiedName().substring(packageName.asString().length + 1)
    return classNames.replace(".", separator)
  }

  fun KSType.isScoped(): Boolean {
    return declaration.requireQualifiedName() == scopedFqName ||
      (declaration as? KSTypeAlias)?.type?.resolve()?.declaration?.requireQualifiedName() ==
      scopedFqName
  }

  @Suppress("ReturnCount")
  fun boundType(clazz: KSClassDeclaration, annotation: KSAnnotation): KSType {
    boundTypeFromAnnotation(annotation)?.let {
      return it
    }

    // The bound type is not defined in the annotation, let's inspect the super types.
    val superTypes =
      clazz.superTypes
        .map { it.resolve() }
        .filter { it.declaration.requireQualifiedName() != anyFqName }
        .toList()

    when (superTypes.size) {
      0 -> {
        val message =
          "The bound type could not be determined for " +
            "${clazz.simpleName.asString()}. There are no super types."
        logger.error(message, clazz)
        throw IllegalArgumentException(message)
      }

      1 -> {
        return superTypes.single()
      }

      else -> {
        if (superTypes.size == 2) {
          // Ignore Scoped as super type.
          superTypes
            .singleOrNull { !it.isScoped() }
            ?.let {
              return it
            }
        }

        val message =
          "The bound type could not be determined for " +
            "${clazz.simpleName.asString()}. There are multiple super types: " +
            superTypes.joinToString { it.declaration.simpleName.asString() } +
            "."
        logger.error(message, clazz)
        throw IllegalArgumentException(message)
      }
    }
  }

  fun boundTypeFromAnnotation(annotation: KSAnnotation): KSType? {
    return annotation.arguments
      .firstOrNull { it.name?.asString() == "boundType" }
      ?.let { it.value as? KSType }
      ?.takeIf { it.declaration.requireQualifiedName() != Unit::class.requireQualifiedName() }
  }

  fun checkNoDuplicateBoundTypes(clazz: KSClassDeclaration, annotations: List<KSAnnotation>) {
    annotations
      .mapNotNull { boundTypeFromAnnotation(it) }
      .map { it.declaration.requireQualifiedName() }
      .takeIf { it.isNotEmpty() }
      ?.reduce { previous, next ->
        check(previous != next, clazz) { "The same type should not be contributed twice: $next." }

        previous
      }
  }

  fun KSClassDeclaration.findAnnotationsAtLeastOne(
    annotation: KClass<out Annotation>,
  ): List<KSAnnotation> {
    return findAnnotations(annotation).also {
      check(it.isNotEmpty(), this) {
        "Couldn't find the @${annotation.simpleName} annotation for $this."
      }
    }
  }
}
