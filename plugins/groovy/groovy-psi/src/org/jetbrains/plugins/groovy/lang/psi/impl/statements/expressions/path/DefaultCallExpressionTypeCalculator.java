// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.path;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.*;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.hash.HashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrClosureType;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrLiteralClassType;
import org.jetbrains.plugins.groovy.lang.psi.impl.signatures.GrClosureSignatureUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.typeEnhancers.GrCallExpressionTypeCalculator;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;

import java.util.Set;

import static org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil.isCompileStatic;

/**
 * @author sergey.evdokimov
 */
public class DefaultCallExpressionTypeCalculator extends GrCallExpressionTypeCalculator {
  private static final Logger LOG = Logger.getInstance(DefaultCallExpressionTypeCalculator.class);

  @Override
  public PsiType calculateReturnType(@NotNull GrMethodCall callExpression, GroovyResolveResult[] resolveResults) {
    GrExpression invoked = callExpression.getInvokedExpression();
    if (invoked instanceof GrReferenceExpression) {
      GrReferenceExpression refExpr = (GrReferenceExpression) invoked;
      PsiManager manager = callExpression.getManager();
      PsiType result = null;
      for (GroovyResolveResult resolveResult : resolveResults) {
        PsiType returnType = calculateReturnTypeInner(callExpression, refExpr, resolveResult);
        if (returnType == null) return null;

        PsiType nonVoid = PsiType.VOID.equals(returnType) && !isCompileStatic(callExpression) ? PsiType.NULL : returnType;

        PsiType normalized;
        if (nonVoid instanceof GrLiteralClassType) {
          normalized = nonVoid;
        }
        else {
          boolean needsSubstitutor = hasGenerics(nonVoid);
          PsiSubstitutor substitutor = needsSubstitutor ? resolveResult.getSubstitutor() : PsiSubstitutor.EMPTY;
          normalized = TypesUtil.substituteAndNormalizeType(nonVoid, substitutor, resolveResult.getSpreadState(), callExpression);
          LOG.assertTrue(normalized != null, "return type: " + returnType + "; substitutor: " + substitutor);
        }
        
        if (result == null || normalized.isAssignableFrom(result)) {
          result = normalized;
        }
        else if (!result.isAssignableFrom(normalized)) {
          result = TypesUtil.getLeastUpperBound(result, normalized, manager);
        }
      }

      return result;
    }
    else {
      return extractReturnTypeFromType(invoked.getType(), false, callExpression);
    }
  }

  @Nullable
  private static PsiType calculateReturnTypeInner(GrMethodCall callExpression,
                                                  GrReferenceExpression refExpr,
                                                  GroovyResolveResult resolveResult) {
    PsiElement resolved = resolveResult.getElement();
    if (resolved instanceof PsiMethod) {
      PsiMethod method = (PsiMethod)resolved;
      if (resolveResult.isInvokedOnProperty()) {
        final PsiType propertyType = PsiUtil.getSmartReturnType(method);
        return extractReturnTypeFromType(propertyType, true, callExpression);
      }
      else {
        PsiType closureReturnType = getClosureMethodsReturnType(callExpression, refExpr, method);
        if (closureReturnType != null) {
          return closureReturnType;
        }
        else {
          final PsiType smartReturnType = PsiUtil.getSmartReturnType(method);
          return smartReturnType;
        }
      }
    }
    else if (resolved instanceof GrVariable) {
      PsiType refType = refExpr.getType();
      refType = TypesUtil.boxPrimitiveType(refType, callExpression.getManager(), callExpression.getResolveScope());
      final PsiType type = refType == null ? ((GrVariable)resolved).getTypeGroovy() : refType;
      return extractReturnTypeFromType(type, false, callExpression);
    }
    return null;
  }

  @Nullable
  private static PsiType extractReturnTypeFromType(PsiType type, boolean returnTypeIfFail, GrMethodCall callExpression) {
    PsiType returnType = returnTypeIfFail ? type: null;
    if (type instanceof GrClosureType) {
      returnType = GrClosureSignatureUtil.getReturnType(((GrClosureType)type).getSignature(), callExpression);
    }
    else if (TypesUtil.isPsiClassTypeToClosure(type)) {
      assert type instanceof PsiClassType;
      final PsiType[] parameters = ((PsiClassType)type).getParameters();
      if (parameters.length == 1) {
        returnType = parameters[0];
      }
    }
    else if (type instanceof PsiClassType) {
      final GrExpression invoked = callExpression.getInvokedExpression();
      final GroovyResolveResult[] calls = ResolveUtil
        .getMethodCandidates(type, "call", invoked != null ? invoked : callExpression, PsiUtil.getArgumentTypes(invoked, false));
      returnType = null;
      final PsiManager manager = callExpression.getManager();
      for (GroovyResolveResult call : calls) {
        final PsiType substituted = ResolveUtil.extractReturnTypeFromCandidate(call, callExpression, PsiUtil.getArgumentTypes(invoked, true));
        returnType = TypesUtil.getLeastUpperBoundNullable(returnType, substituted, manager);
      }
    }
    return returnType;
  }


  private static final Set<String> CLOSURE_METHODS = new HashSet<>();
  static {
    CLOSURE_METHODS.add("call");
    CLOSURE_METHODS.add("curry");
    CLOSURE_METHODS.add("ncurry");
    CLOSURE_METHODS.add("rcurry");
    CLOSURE_METHODS.add("memoize");
    CLOSURE_METHODS.add("trampoline");
  }
  @Nullable
  private static PsiType getClosureMethodsReturnType(GrMethodCall callExpression, GrReferenceExpression refExpr, PsiMethod resolved) {
    PsiClass clazz = resolved.getContainingClass();
    if (clazz == null || !GroovyCommonClassNames.GROOVY_LANG_CLOSURE.equals(clazz.getQualifiedName())) return null;

    if (!CLOSURE_METHODS.contains(resolved.getName())) return null;

    GrExpression qualifier = refExpr.getQualifierExpression();
    if (qualifier == null) return null;

    PsiType qType = qualifier.getType();
    if (!(qType instanceof GrClosureType)) return null;

    if ("call".equals(resolved.getName())) {
      return GrClosureSignatureUtil.getReturnType(((GrClosureType)qType).getSignature(), callExpression);
    }
    else if ("curry".equals(resolved.getName()) || "trampoline".equals(resolved.getName())) {
      return ((GrClosureType)qType).curry(PsiUtil.getArgumentTypes(refExpr, false), 0, callExpression);
    }
    else if ("memoize".equals(resolved.getName())) {
      return qType;
    }
    else if ("rcurry".equals(resolved.getName())) {
      return ((GrClosureType)qType).curry(PsiUtil.getArgumentTypes(refExpr, false), -1, callExpression);
    }
    else if ("ncurry".equals(resolved.getName())) {
      final GrArgumentList argList = callExpression.getArgumentList();
      final GrExpression[] arguments = argList.getExpressionArguments();
      if (arguments.length > 0) {
        final GrExpression first = arguments[0];
        if (first instanceof GrLiteral) {
          final Object value = ((GrLiteral)first).getValue();
          if (value instanceof Integer) {
            final PsiType[] argTypes = PsiUtil.getArgumentTypes(refExpr, false);
            if (argTypes != null) {
              return ((GrClosureType)qType).curry(ArrayUtil.remove(argTypes, 0), (Integer)value, callExpression);
            }
          }
        }
      }
      return qType;
    }
    return null;
  }

  private static boolean hasGenerics(@NotNull PsiType type) {
    if (!Registry.is("groovy.return.type.optimization")) {
      // optimization is disabled, fall back to old behaviour
      return true;
    }
    return type.accept(new PsiTypeVisitor<Boolean>() {

      @Nullable
      @Override
      public Boolean visitClassType(PsiClassType classType) {
        if (classType.resolve() instanceof PsiTypeParameter) {
          return true;
        }
        if (!classType.hasParameters()) return null;
        for (PsiType parameter : classType.getParameters()) {
          if (parameter.accept(this) == Boolean.TRUE) return true;
        }
        return null;
      }

      @Nullable
      @Override
      public Boolean visitArrayType(PsiArrayType arrayType) {
        return arrayType.getComponentType().accept(this);
      }

      @Nullable
      @Override
      public Boolean visitWildcardType(PsiWildcardType wildcardType) {
        final PsiType bound = wildcardType.getBound();
        return bound == null ? null : bound.accept(this);
      }
    }) == Boolean.TRUE;
  }
}
