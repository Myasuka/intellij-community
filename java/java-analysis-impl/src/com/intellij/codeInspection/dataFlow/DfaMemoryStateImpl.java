// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.*;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import gnu.trove.THashMap;
import gnu.trove.TIntArrayList;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectProcedure;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Invariant: qualifiers of the variables used in myEqClasses or myVariableStates must be canonical variables
 * where canonical variable is the minimal DfaVariableValue inside its eqClass, according to EqClass#CANONICAL_VARIABLE_COMPARATOR.
 */
public class DfaMemoryStateImpl implements DfaMemoryState {
  private static final Logger LOG = Logger.getInstance(DfaMemoryStateImpl.class);

  private final DfaValueFactory myFactory;

  private final List<EqClass> myEqClasses;
  // dfa value id -> indices in myEqClasses list of the classes which contain the id
  private final MyIdMap myIdToEqClassesIndices;
  private final Stack<DfaValue> myStack;
  private final DistinctPairSet myDistinctClasses;
  private final LinkedHashMap<DfaVariableValue,DfaVariableState> myVariableStates;
  private final Map<DfaVariableValue,DfaVariableState> myDefaultVariableStates;
  private boolean myEphemeral;

  protected DfaMemoryStateImpl(final DfaValueFactory factory) {
    myFactory = factory;
    myDefaultVariableStates = new THashMap<>();
    myEqClasses = new ArrayList<>();
    myVariableStates = new LinkedHashMap<>();
    myDistinctClasses = new DistinctPairSet(this);
    myStack = new Stack<>();
    myIdToEqClassesIndices = new MyIdMap();
  }

  protected DfaMemoryStateImpl(DfaMemoryStateImpl toCopy) {
    myFactory = toCopy.myFactory;
    myEphemeral = toCopy.myEphemeral;
    myDefaultVariableStates = toCopy.myDefaultVariableStates; // shared between all states

    myStack = new Stack<>(toCopy.myStack);
    myDistinctClasses = new DistinctPairSet(this, toCopy.myDistinctClasses);

    myEqClasses = new ArrayList<>(toCopy.myEqClasses);
    myIdToEqClassesIndices = (MyIdMap)toCopy.myIdToEqClassesIndices.clone();
    myVariableStates = new LinkedHashMap<>(toCopy.myVariableStates);

    myCachedNonTrivialEqClasses = toCopy.myCachedNonTrivialEqClasses;
    myCachedHash = toCopy.myCachedHash;
  }

  @NotNull
  public DfaValueFactory getFactory() {
    return myFactory;
  }

  @NotNull
  @Override
  public DfaMemoryStateImpl createCopy() {
    return new DfaMemoryStateImpl(this);
  }

  @NotNull
  @Override
  public DfaMemoryStateImpl createClosureState() {
    DfaMemoryStateImpl copy = createCopy();
    for (DfaVariableValue value : new ArrayList<>(copy.myVariableStates.keySet())) {
      DfType dfType = getDfType(value);
      if (dfType instanceof DfReferenceType) {
        copy.setDfType(value, ((DfReferenceType)dfType).dropLocality());
      }
    }
    copy.flushFields();
    copy.emptyStack();
    return copy;
  }

  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (!(obj instanceof DfaMemoryStateImpl)) return false;
    DfaMemoryStateImpl that = (DfaMemoryStateImpl)obj;
    if (myCachedHash != null && that.myCachedHash != null && !myCachedHash.equals(that.myCachedHash)) return false;
    return myEphemeral == that.myEphemeral && myStack.equals(that.myStack) &&
           getNonTrivialEqClasses().equals(that.getNonTrivialEqClasses()) &&
           getDistinctClassPairs().equals(that.getDistinctClassPairs()) &&
           myVariableStates.equals(that.myVariableStates);
  }

  Object getSuperficialKey() {
    return Pair.create(myEphemeral, myStack);
  }

  DistinctPairSet getDistinctClassPairs() {
    return myDistinctClasses;
  }

  private LinkedHashSet<EqClass> myCachedNonTrivialEqClasses;
  LinkedHashSet<EqClass> getNonTrivialEqClasses() {
    if (myCachedNonTrivialEqClasses != null) return myCachedNonTrivialEqClasses;

    LinkedHashSet<EqClass> result = new LinkedHashSet<>();
    for (EqClass eqClass : myEqClasses) {
      if (eqClass != null && eqClass.size() > 1) {
        result.add(eqClass);
      }
    }
    return myCachedNonTrivialEqClasses = result;
  }

  private Integer myCachedHash;
  public int hashCode() {
    if (myCachedHash != null) return myCachedHash;

    int hash = ((getNonTrivialEqClasses().hashCode() * 31 +
                 getDistinctClassPairs().hashCode()) * 31 +
                 myStack.hashCode()) * 31 + myVariableStates.hashCode();
    return myCachedHash = hash;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append('<');
    if (myEphemeral) {
      result.append("ephemeral, ");
    }

    for (EqClass set : getNonTrivialEqClasses()) {
      result.append(set);
    }

    if (!myDistinctClasses.isEmpty()) {
      result.append("\n  distincts: ");
      String distincts = StreamEx.of(getDistinctClassPairs()).map(DistinctPairSet.DistinctPair::toString).sorted().joining(" ");
      result.append(distincts);
    }

    if (!myStack.isEmpty()) {
      result.append("\n  stack: ").append(StringUtil.join(myStack, ","));
    }
    if (!myVariableStates.isEmpty()) {
      result.append("\n  vars: ");
      myVariableStates.forEach((key, value) -> result.append("[").append(key).append("->").append(value).append("] "));
    }
    result.append('>');
    return result.toString();
  }

  @NotNull
  @Override
  public DfaValue pop() {
    myCachedHash = null;
    return myStack.pop();
  }

  @NotNull
  @Override
  public DfaValue peek() {
    return myStack.peek();
  }

  @Nullable
  @Override
  public DfaValue getStackValue(int offset) {
    int index = myStack.size() - 1 - offset;
    return index < 0 ? null : myStack.get(index);
  }

  @Override
  public void push(@NotNull DfaValue value) {
    myCachedHash = null;
    myStack.push(value);
  }

  @Override
  public void emptyStack() {
    while (!myStack.isEmpty() && !(myStack.peek() instanceof DfaControlTransferValue)) {
      myCachedHash = null;
      myStack.pop();
    }
  }

  @Override
  public void setVarValue(DfaVariableValue var, DfaValue value) {
    if (var == value) return;

    value = handleStackValueOnVariableFlush(value, var, null);
    flushVariable(var, var.getInherentNullability() != Nullability.UNKNOWN);
    flushQualifiedMethods(var);

    if (DfaTypeValue.isUnknown(value)) {
      setVariableState(var, getVariableState(var).withNotNull());
      return;
    }

    DfaVariableState state = getVariableState(var).withValue(value);
    DfType dfType = filterDfTypeOnAssignment(var, getDfType(value)).meet(var.getDfType());
    if (dfType == DfTypes.BOTTOM) return; // likely uncompilable code or bad CFG
    if (value instanceof DfaVariableValue && !ControlFlowAnalyzer.isTempVariable(var) && 
        !ControlFlowAnalyzer.isTempVariable((DfaVariableValue)value) &&
        (var.getQualifier() == null || !ControlFlowAnalyzer.isTempVariable(var.getQualifier()))) {
      // assigning a = b when b is known to be null: could be ephemeral
      checkEphemeral(var, value);
    }
    setVariableState(var, state.createCopy(dfType));
    applyRelation(var, value, false);
    Couple<DfaValue> specialFields = getSpecialEquivalencePair(var, value);
    if (specialFields != null && specialFields.getFirst() instanceof DfaVariableValue) {
      setVarValue((DfaVariableValue)specialFields.getFirst(), specialFields.getSecond());
    }
  }

  protected DfType filterDfTypeOnAssignment(DfaVariableValue var, @NotNull DfType dfType) {
    return dfType;
  }

  private DfaValue handleStackValueOnVariableFlush(DfaValue value,
                                                   DfaVariableValue flushed,
                                                   DfaVariableValue replacement) {
    if (value.dependsOn(flushed)) {
      DfType dfType = getDfType(value);
      if (value instanceof DfaVariableValue) {
        if (replacement != null) {
          DfaVariableValue target = replaceQualifier((DfaVariableValue)value, flushed, replacement);
          if (target != value) return target;
        }
      }
      return myFactory.fromDfType(dfType);
    }
    return value;
  }

  private int getOrCreateEqClassIndex(@NotNull DfaVariableValue dfaValue) {
    int i = getEqClassIndex(dfaValue);
    if (i != -1) return i;
    dfaValue = canonicalize(dfaValue);
    EqClass eqClass = new EqClass(myFactory);
    eqClass.add(dfaValue.getID());

    int resultIndex = storeClass(eqClass);
    checkInvariants();

    return resultIndex;
  }

  private int storeClass(EqClass eqClass) {
    int freeIndex = myEqClasses.indexOf(null);
    int resultIndex = freeIndex >= 0 ? freeIndex : myEqClasses.size();
    if (freeIndex >= 0) {
      myEqClasses.set(freeIndex, eqClass);
    }
    else {
      myEqClasses.add(eqClass);
    }
    eqClass.forEach(id -> {
      myIdToEqClassesIndices.put(id, resultIndex);
      return true;
    });
    return resultIndex;
  }

  /**
   * Returns true if current state describes all possible concrete program states described by {@code that} state.
   *
   * @param that a sub-state candidate
   * @return true if current state is a super-state of the supplied state.
   */
  public boolean isSuperStateOf(DfaMemoryStateImpl that) {
    if (myEphemeral && !that.myEphemeral) return false;
    if (myStack.size() != that.myStack.size()) return false;
    for (int i = 0; i < myStack.size(); i++) {
      if (!isSuperValue(myStack.get(i), that.myStack.get(i))) return false;
    }
    int[] thisToThat = getClassesMap(that);
    if (thisToThat == null) return false;
    for (DistinctPairSet.DistinctPair pair : myDistinctClasses) {
      int firstIndex = thisToThat[pair.getFirstIndex()];
      int secondIndex = thisToThat[pair.getSecondIndex()];
      if (firstIndex == -1 || secondIndex == -1 || firstIndex == secondIndex) return false;
      RelationType relation = that.myDistinctClasses.getRelation(firstIndex, secondIndex);
      if (relation == null || pair.isOrdered() && relation != RelationType.LT) return false;
    }
    Set<DfaVariableValue> values = new HashSet<>(this.myVariableStates.keySet());
    values.addAll(that.myVariableStates.keySet());
    for (DfaVariableValue value : values) {
      // the default variable state is not always a superstate for any non-default state
      // (e.g. default can be nullable, but current state can be notnull)
      // so we cannot limit checking to myVariableStates map only
      DfaVariableState thisState = this.getVariableState(value);
      DfaVariableState thatState = that.getVariableState(value);
      if(!thisState.isSuperStateOf(thatState)) return false;
    }
    return true;
  }

  /**
   * Returns an int array which maps this state class indices to that state class indices.
   *
   * @param that other state to map class indices
   * @return an int array which values are indices of the corresponding that state class which contains
   * all the values from this state class or -1 if there's no corresponding that state class.
   * Null is returned if at least one of this state classes contains values which do not belong to the same
   * class in that state
   */
  @Nullable
  private int[] getClassesMap(DfaMemoryStateImpl that) {
    List<EqClass> thisClasses = this.myEqClasses;
    List<EqClass> thatClasses = that.myEqClasses;
    int thisSize = thisClasses.size();
    int thatSize = thatClasses.size();
    int[] thisToThat = new int[thisSize];
    // If any two values are equivalent in this, they also must be equivalent in that
    for (int thisIdx = 0; thisIdx < thisSize; thisIdx++) {
      EqClass thisClass = thisClasses.get(thisIdx);
      thisToThat[thisIdx] = -1;
      if (thisClass != null) {
        boolean found = false;
        for (int thatIdx = 0; thatIdx < thatSize; thatIdx++) {
          EqClass thatClass = thatClasses.get(thatIdx);
          if (thatClass != null && thatClass.containsAll(thisClass)) {
            thisToThat[thisIdx] = thatIdx;
            found = true;
            break;
          }
        }
        if (!found && thisClass.size() > 1) return null;
      }
    }
    return thisToThat;
  }

  @Override
  public boolean shouldCompareByEquals(DfaValue dfaLeft, DfaValue dfaRight) {
    if (dfaLeft == dfaRight && !(dfaLeft instanceof DfaBoxedValue) && !(dfaLeft.getDfType() instanceof DfConstantType)) {
      return false;
    }
    return !isNull(dfaLeft) && !isNull(dfaRight) &&
           DfaUtil.isComparedByEquals(getPsiType(dfaLeft)) && DfaUtil.isComparedByEquals(getPsiType(dfaRight));
  }

  private static boolean isSuperValue(DfaValue superValue, DfaValue subValue) {
    if (DfaTypeValue.isUnknown(superValue) || superValue == subValue) return true;
    if (superValue instanceof DfaTypeValue && subValue instanceof DfaTypeValue) {
      return superValue.getDfType().isMergeable(subValue.getDfType());
    }
    return false;
  }

  List<EqClass> getEqClasses() {
    return myEqClasses;
  }

  @Nullable
  private EqClass getEqClass(DfaValue value) {
    int index = getEqClassIndex(value);
    return index == -1 ? null : myEqClasses.get(index);
  }

  /**
   * Returns existing equivalence class index or -1 if not found
   * @param dfaValue value to find a class for
   * @return class index or -1 if not found
   */
  int getEqClassIndex(@NotNull DfaValue dfaValue) {
    Integer classIndex = myIdToEqClassesIndices.get(dfaValue.getID());
    if (classIndex == null) {
      dfaValue = canonicalize(dfaValue);
      classIndex = myIdToEqClassesIndices.get(dfaValue.getID());
    }

    if (classIndex == null) return -1;

    EqClass aClass = myEqClasses.get(classIndex);
    assert aClass.contains(dfaValue.getID());
    return classIndex;
  }

  DfaVariableValue getCanonicalVariable(DfaValue val) {
    EqClass eqClass = getEqClass(val);
    return eqClass == null ? null : eqClass.getCanonicalVariable();
  }

  /**
   * Unite equivalence classes containing given values
   *
   * @param val1 the first value
   * @param val2 the second value
   * @return true if classes were successfully united.
   */
  private boolean uniteClasses(DfaVariableValue val1, DfaVariableValue val2) {
    DfaVariableValue var1 = getCanonicalVariable(val1);
    DfaVariableValue var2 = getCanonicalVariable(val2);
    int c1Index = getOrCreateEqClassIndex(val1);
    int c2Index = getOrCreateEqClassIndex(val2);
    if (c1Index == c2Index) return true;

    if (!myDistinctClasses.unite(c1Index, c2Index)) return false;

    EqClass c1 = myEqClasses.get(c1Index);
    EqClass c2 = myEqClasses.get(c2Index);

    EqClass newClass = new EqClass(c1);

    myEqClasses.set(c1Index, newClass);
    for (int i = 0; i < c2.size(); i++) {
      int c = c2.get(i);
      newClass.add(c);
      myIdToEqClassesIndices.put(c, c1Index);
    }

    myEqClasses.set(c2Index, null);
    checkInvariants();

    if (var1 == null || var2 == null || var1 == var2) return true;
    int compare = EqClass.CANONICAL_VARIABLE_COMPARATOR.compare(var1, var2);
    return compare < 0 ? convertQualifiers(var2, var1) : convertQualifiers(var1, var2);
  }

  private static DfaVariableValue replaceQualifier(DfaVariableValue variable, DfaVariableValue from, DfaVariableValue to) {
    DfaVariableValue qualifier = variable.getQualifier();
    if (qualifier != null) {
      return variable.withQualifier(replaceQualifier(qualifier == from ? to : qualifier, from, to));
    }
    return variable;
  }

  private boolean convertQualifiers(DfaVariableValue from, DfaVariableValue to) {
    assert from != to;
    if (from.getDependentVariables().isEmpty()) return true;
    List<DfaVariableValue> vars = new ArrayList<>(myVariableStates.keySet());
    for (DfaVariableValue var : vars) {
      DfaVariableValue target = replaceQualifier(var, from, to);
      if (target != var) {
        DfaVariableState fromState = myVariableStates.remove(var);
        if (fromState != null) {
          DfaVariableState toState = myVariableStates.get(target);
          if (toState == null) {
            toState = fromState;
          }
          else {
            toState = fromState.meet(toState.myDfType);
            if (toState == null) return false;
          }
          setVariableState(target, toState);
        }
      }
    }
    for (int valueId : myIdToEqClassesIndices.keys()) {
      DfaValue value = myFactory.getValue(valueId);
      DfaVariableValue var = ObjectUtils.tryCast(value, DfaVariableValue.class);
      if (var == null || var.getQualifier() != from) continue;
      DfaVariableValue target = var.withQualifier(to);
      if (!uniteClasses(var, target)) return false;
      removeEquivalence(var);
    }
    return true;
  }

  private void checkInvariants() {
    if (!LOG.isDebugEnabled() && !ApplicationManager.getApplication().isEAP()) return;
    myIdToEqClassesIndices.forEachEntry((id, classIndex) -> {
      EqClass eqClass = myEqClasses.get(classIndex);
      if (eqClass == null || !eqClass.contains(id)) {
        LOG.error("Invariant violated: null-class for id=" + myFactory.getValue(id));
      }
      return true;
    });
    myDistinctClasses.forEach(DistinctPairSet.DistinctPair::check);
  }

  @Override
  public boolean isNull(DfaValue dfaValue) {
    return getDfType(dfaValue) == DfTypes.NULL;
  }

  @Override
  public boolean isNotNull(DfaValue dfaVar) {
    return !getDfType(dfaVar).isSuperType(DfTypes.NULL);
  }

  @Override
  public void markEphemeral() {
    myEphemeral = true;
  }

  @Override
  public boolean isEphemeral() {
    return myEphemeral;
  }

  @Override
  public boolean isEmptyStack() {
    return myStack.isEmpty();
  }

  @Override
  public boolean castTopOfStack(@NotNull DfaPsiType type) {
    DfaValue value = peek();
    DfType dfType = getDfType(value);
    DfType result = dfType.meet(type.asConstraint().asDfType());
    if (!result.equals(dfType)) {
      if (result == DfTypes.NULL || !meetDfType(value, result)) return false;
      if (!(value instanceof DfaVariableValue)) {
        pop();
        push(myFactory.fromDfType(result));
      }
    }
    return true;
  }

  private void convertReferenceEqualityToValueEquality(DfaValue value) {
    int id = canonicalize(value).getID();
    Integer index = myIdToEqClassesIndices.get(id);
    if (index == null) return;
    for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
      DistinctPairSet.DistinctPair pair = iterator.next();
      EqClass otherClass = pair.getOtherClass(index);
      if (otherClass != null && !isNull(otherClass.getVariable(0))) {
        iterator.remove();
      }
    }
  }

  @Override
  public void setDfType(@NotNull DfaValue value, @NotNull DfType dfType) {
    if (value instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)value;
      DfaVariableState state = getVariableState(var);
      if (DfaNullability.fromDfType(state.myDfType) != DfaNullability.fromDfType(dfType)) {
        removeEquivalence(var);
      }
      setVariableState(var, state.createCopy(dfType));
    }
  }
  
  @NotNull
  private static DfType sanitizeNullability(@NotNull DfType dfType) {
    if (!(dfType instanceof DfReferenceType)) return dfType;
    DfaNullability nullability = ((DfReferenceType)dfType).getNullability();
    if (nullability == DfaNullability.NULLABLE) return ((DfReferenceType)dfType).dropNullability();
    return dfType;
  }

  @Override
  public boolean meetDfType(@NotNull DfaValue value, @NotNull DfType dfType) {
    if (dfType == DfTypes.TOP) return true;
    if (dfType == DfTypes.BOTTOM) return false;
    if (value instanceof DfaBinOpValue) {
      return propagateRangeBack(DfLongType.extractRange(dfType), (DfaBinOpValue)value);
    }
    if (value instanceof DfaVariableValue) {
      DfaVariableValue var = (DfaVariableValue)value;
      DfaVariableState state = getVariableState(var);
      DfType result = state.myDfType.meet(dfType);
      if (result.equals(state.myDfType)) return true;
      if (result == DfTypes.BOTTOM) return false;
      DfaVariableState newState = state.createCopy(result);
      setVariableState(var, newState);
      if (DfaUtil.isComparedByEquals(newState.getTypeConstraint().getPsiType()) &&
          !newState.getTypeConstraint().equals(state.getTypeConstraint())) {
        // Type is narrowed to java.lang.String, java.lang.Integer, etc.: we consider String & boxed types
        // equivalence by content, but other object types by reference, so we need to remove distinct pairs, if any.
        convertReferenceEqualityToValueEquality(value);
      }
      updateEquivalentVariables(var, newState);
      if (result instanceof DfConstantType) {
        if (!propagateConstant(var, (DfConstantType<?>)result)) return false;
      }
      if (result instanceof DfAntiConstantType) {
        for (Object notValue : ((DfAntiConstantType<?>)result).getNotValues()) {
          if (notValue instanceof PsiType) {
            if (processGetClass(var, (PsiType)notValue, true) == ThreeState.NO) return false;
          }
        }
      }
      if (result instanceof DfIntegralType) {
        if (!applyRangeToRelatedValues(var, ((DfIntegralType)result).getRange())) return false;
      }
      return true;
    }
    return value.getDfType().meet(dfType) != DfTypes.BOTTOM;
  }

  private boolean propagateRangeBack(@NotNull LongRangeSet factValue, @NotNull DfaBinOpValue binOp) {
    boolean isLong = PsiType.LONG.equals(binOp.getType());
    LongRangeSet appliedRange = isLong ? factValue : factValue.intersect(Objects.requireNonNull(LongRangeSet.fromType(PsiType.INT)));
    DfaVariableValue left = binOp.getLeft();
    DfaValue right = binOp.getRight();
    DfIntegralType leftDfType = ObjectUtils.tryCast(getDfType(left), DfIntegralType.class);
    DfIntegralType rightDfType = ObjectUtils.tryCast(getDfType(right), DfIntegralType.class);
    if(leftDfType == null || rightDfType == null) return true;
    LongRangeSet leftRange = leftDfType.getRange();
    LongRangeSet rightRange = rightDfType.getRange();
    LongRangeSet result = getBinOpRange(binOp);
    assert result != null;
    if (!result.intersects(appliedRange)) return false;
    LongRangeSet leftConstraint = LongRangeSet.all();
    LongRangeSet rightConstraint = LongRangeSet.all();
    switch (binOp.getOperation()) {
      case PLUS:
        leftConstraint = appliedRange.minus(rightRange, isLong);
        rightConstraint = appliedRange.minus(leftRange, isLong);
        break;
      case MINUS:
        leftConstraint = rightRange.plus(appliedRange, isLong);
        rightConstraint = leftRange.minus(appliedRange, isLong);
        break;
      case REM:
        Long value = rightRange.getConstantValue();
        if (value != null) {
          leftConstraint = LongRangeSet.fromRemainder(value, appliedRange.intersect(result));
        }
        break;
    }
    return meetDfType(left, leftDfType.meetRange(leftConstraint)) && meetDfType(right, rightDfType.meetRange(rightConstraint));
  }

  @Override
  public boolean applyContractCondition(DfaCondition condition) {
    if (condition instanceof DfaRelation) {
      DfaRelation relation = (DfaRelation)condition;
      if (relation.isEquality()) {
        checkEphemeral(relation.getLeftOperand(), relation.getRightOperand());
      }
    }
    return applyCondition(condition);
  }

  @Override
  public boolean areEqual(@NotNull DfaValue value1, @NotNull DfaValue value2) {
    if (value1 instanceof DfaBinOpValue && value2 instanceof DfaBinOpValue) {
      DfaBinOpValue binOp1 = (DfaBinOpValue)value1;
      DfaBinOpValue binOp2 = (DfaBinOpValue)value2;
      return binOp1.getOperation() == binOp2.getOperation() &&
             areEqual(binOp1.getLeft(), binOp2.getLeft()) &&
             areEqual(binOp1.getRight(), binOp2.getRight());
    }
    DfType dfType1 = getDfType(value1);
    DfType dfType2 = getDfType(value2);
    if (dfType1 instanceof DfConstantType && dfType2 instanceof DfConstantType && dfType1.equals(dfType2)) return true;
    if (!(value1 instanceof DfaVariableValue)) return false;
    if (!(value2 instanceof DfaVariableValue)) return false;
    if (value1 == value2) return true;
    int index1 = getEqClassIndex(value1);
    int index2 = getEqClassIndex(value2);
    return index1 != -1 && index1 == index2;
  }

  @Nullable
  @Override
  public RelationType getRelation(DfaValue left, DfaValue right) {
    int leftClass = getEqClassIndex(left);
    int rightClass = getEqClassIndex(right);
    if (leftClass == -1 || rightClass == -1) return null;
    if (leftClass == rightClass) return RelationType.EQ;
    return myDistinctClasses.getRelation(leftClass, rightClass);
  }

  @Override
  public boolean applyCondition(DfaCondition dfaCond) {
    if (!(dfaCond instanceof DfaRelation)) {
      return dfaCond != DfaCondition.getFalse();
    }
    return applyRelationCondition((DfaRelation)dfaCond);
  }

  private boolean applyRelationCondition(@NotNull DfaRelation dfaRelation) {
    DfaValue dfaLeft = dfaRelation.getLeftOperand();
    DfaValue dfaRight = dfaRelation.getRightOperand();
    RelationType relationType = dfaRelation.getRelation();

    if (DfaTypeValue.isUnknown(dfaLeft) || DfaTypeValue.isUnknown(dfaRight)) return true;

    DfType leftType = getDfType(dfaLeft);
    DfType rightType = getDfType(dfaRight);

    if (leftType instanceof DfIntegralType && rightType instanceof DfIntegralType && relationType.getFlipped() != null) {
      if (!meetDfType(dfaLeft, ((DfIntegralType)leftType).meetRelation(relationType, rightType)) ||
          !meetDfType(dfaRight, ((DfIntegralType)rightType).meetRelation(relationType.getFlipped(), leftType))) {
        return false;
      }
      if (!applyBinOpRelations(dfaLeft, relationType, dfaRight)) return false;
      if (!(dfaRight instanceof DfaVariableValue)) return true;
      return applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
    }

    if (leftType instanceof DfFloatingPointType && rightType instanceof DfFloatingPointType && relationType.getFlipped() != null) {
      if (isNaN(leftType) || isNaN(rightType)) {
        applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
        return relationType == RelationType.NE;
      }
      RelationType constantRelation = getFloatingConstantRelation(leftType, rightType);
      if (constantRelation != null) {
        return constantRelation == relationType;
      }
      if (canBeNaN(leftType) || canBeNaN(rightType)) {
        if (dfaLeft == dfaRight && dfaLeft instanceof DfaVariableValue && !(dfaLeft.getType() instanceof PsiPrimitiveType)) {
          return !dfaRelation.isNonEquality();
        }
        applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
        return true;
      }
      return applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
    }

    if (dfaRight instanceof DfaTypeValue) {
      if ((relationType == RelationType.EQ || relationType.isInequality()) &&
          !applyUnboxedRelation(dfaLeft, dfaRight, relationType.isInequality())) {
        return false;
      }
      if (relationType == RelationType.EQ && !applySpecialFieldEquivalence(dfaLeft, dfaRight)) {
        return false;
      }
      if (relationType == RelationType.IS || relationType == RelationType.EQ) {
        return meetDfType(dfaLeft, sanitizeNullability(rightType));
      }
      else if (relationType == RelationType.IS_NOT || (relationType == RelationType.NE && rightType instanceof DfConstantType)) {
        DfType antiType = rightType.tryNegate();
        if (antiType != null && !meetDfType(dfaLeft, antiType)) {
          return leftType.meet(rightType) == DfTypes.BOTTOM;
        }
      }
      return true;
    }

    return applyEquivalenceRelation(relationType, dfaLeft, dfaRight);
  }

  private boolean applyBinOpRelations(DfaValue left, RelationType type, DfaValue right) {
    if (type != RelationType.LT && type != RelationType.GT && type != RelationType.NE && type != RelationType.EQ) return true;
    if (left instanceof DfaBinOpValue) {
      DfaBinOpValue sum = (DfaBinOpValue)left;
      DfaBinOpValue.BinOp op = sum.getOperation();
      if (op != DfaBinOpValue.BinOp.PLUS && op != DfaBinOpValue.BinOp.MINUS) return true;
      LongRangeSet leftRange = DfLongType.extractRange(getDfType(sum.getLeft()));
      LongRangeSet rightRange = DfLongType.extractRange(getDfType(sum.getRight()));
      boolean isLong = PsiType.LONG.equals(sum.getType());
      LongRangeSet rightNegated = rightRange.negate(isLong);
      LongRangeSet rightCorrected = op == DfaBinOpValue.BinOp.MINUS ? rightNegated : rightRange;

      LongRangeSet resultRange = DfLongType.extractRange(getDfType(right));
      RelationType correctedRelation = correctRelation(type, leftRange, rightCorrected, resultRange, isLong);
      if (op == DfaBinOpValue.BinOp.MINUS) {
        long min = resultRange.min();
        long max = resultRange.max();
        if (min == 0 && max == 0) {
          // a-b (rel) 0 => a (rel) b
          if (!applyCondition(sum.getLeft().cond(correctedRelation, sum.getRight()))) return false;
        }
        else if (min == 0 && type == RelationType.GT || min >= 1 && RelationType.GE.isSubRelation(type)) {
          RelationType correctedGt = correctRelation(RelationType.GT, leftRange, rightCorrected, resultRange, isLong);
          if (!applyCondition(sum.getLeft().cond(correctedGt, sum.getRight()))) return false;
        }
        else if (max == 0 && type == RelationType.LT || max <= -1 && RelationType.LE.isSubRelation(type)) {
          RelationType correctedLt = correctRelation(RelationType.LT, leftRange, rightCorrected, resultRange, isLong);
          if (!applyCondition(sum.getLeft().cond(correctedLt, sum.getRight()))) return false;
        }
        if (RelationType.EQ.equals(type) && !resultRange.contains(0)) {
          // a-b == non-zero => a != b
          if (!applyRelation(sum.getLeft(), sum.getRight(), true)) return false;
        }
      }
      if (op == DfaBinOpValue.BinOp.PLUS && RelationType.EQ == type &&
          !resultRange.intersects(LongRangeSet.all().mul(LongRangeSet.point(2), true))) {
        // a+b == odd => a != b
        if (!applyRelation(sum.getLeft(), sum.getRight(), true)) return false;
      }
      if (right instanceof DfaVariableValue) {
        // a+b (rel) c && a == c => b (rel) 0
        if (areEqual(sum.getLeft(), right)) {
          RelationType finalRelation = op == DfaBinOpValue.BinOp.MINUS ?
                                       Objects.requireNonNull(correctedRelation.getFlipped()) : correctedRelation;
          if (!applyCondition(sum.getRight().cond(finalRelation, myFactory.getInt(0)))) return false;
        }
        // a+b (rel) c && b == c => a (rel) 0
        if (op == DfaBinOpValue.BinOp.PLUS && areEqual(sum.getRight(), right)) {
          if (!applyCondition(sum.getLeft().cond(correctedRelation, myFactory.getInt(0)))) return false;
        }

        if (!leftRange.subtractionMayOverflow(op == DfaBinOpValue.BinOp.MINUS ? rightRange : rightNegated, isLong)) {
          // a-positiveNumber >= b => a > b
          if (rightCorrected.max() < 0 && RelationType.GE.isSubRelation(type)) {
            if (!applyLessThanRelation(right, sum.getLeft())) return false;
          }
          // a+positiveNumber >= b => a > b
          if (rightCorrected.min() > 0 && RelationType.LE.isSubRelation(type)) {
            if (!applyLessThanRelation(sum.getLeft(), right)) return false;
          }
        }
        if (RelationType.EQ == type && !rightRange.contains(0)) {
          // a+nonZero == b => a != b
          if (!applyRelation(sum.getLeft(), right, true)) return false;
        }
      }
    }
    return true;
  }

  private static RelationType correctRelation(RelationType relation, LongRangeSet summand1, LongRangeSet summand2,
                                              LongRangeSet resultRange, boolean isLong) {
    if (relation != RelationType.LT && relation != RelationType.GT) return relation;
    boolean overflowPossible = true;
    if (!isLong) {
      LongRangeSet overflowRange = getIntegerSumOverflowValues(summand1, summand2);
      overflowPossible = !overflowRange.isEmpty() && (resultRange == null || resultRange.fromRelation(relation).intersects(overflowRange));
    }
    return overflowPossible ? RelationType.NE : relation;
  }

  @NotNull
  private static LongRangeSet getIntegerSumOverflowValues(LongRangeSet left, LongRangeSet right) {
    if (left.isEmpty() || right.isEmpty()) return LongRangeSet.empty();
    long sumMin = left.min() + right.min();
    long sumMax = left.max() + right.max();
    LongRangeSet result = LongRangeSet.empty();
    if (sumMin < Integer.MIN_VALUE) {
      result = result.unite(LongRangeSet.range((int)sumMin, Integer.MAX_VALUE));
    }
    if (sumMax > Integer.MAX_VALUE) {
      result = result.unite(LongRangeSet.range(Integer.MIN_VALUE, (int)sumMax));
    }
    return result;
  }

  private boolean applyEquivalenceRelation(RelationType type, DfaValue dfaLeft, DfaValue dfaRight) {
    boolean isNegated = type == RelationType.NE || type == RelationType.GT || type == RelationType.LT;
    if (!isNegated && type != RelationType.EQ) {
      return true;
    }

    DfType leftType = getDfType(dfaLeft);
    DfType rightType = getDfType(dfaRight);

    if (type == RelationType.EQ) {
      if (dfaLeft instanceof DfaVariableValue && dfaRight instanceof DfaVariableValue) {
        checkEphemeral(dfaLeft, dfaRight);
        checkEphemeral(dfaRight, dfaLeft);
      }
      if (!meetDfType(dfaLeft, sanitizeNullability(rightType))) return false;
      if (!meetDfType(dfaRight, sanitizeNullability(leftType))) return false;
      if (!applySpecialFieldEquivalence(dfaLeft, dfaRight)) return false;
    } else {
      if (leftType instanceof DfConstantType) {
        DfType antiType = leftType.tryNegate();
        if (antiType != null && !meetDfType(dfaRight, antiType)) return false;
      }
      if (rightType instanceof DfConstantType) {
        DfType antiType = rightType.tryNegate();
        if (antiType != null && !meetDfType(dfaLeft, antiType)) return false;
      }
    }
    if (dfaLeft instanceof DfaVariableValue && dfaRight instanceof DfaVariableValue && !isNegated) {
      if (!equalizeTypesOnGetClass((DfaVariableValue)dfaLeft, (DfaVariableValue)dfaRight)) {
        return false;
      }
    }

    if (dfaLeft == dfaRight) {
      return !isNegated || (dfaLeft instanceof DfaVariableValue && ((DfaVariableValue)dfaLeft).containsCalls());
    }

    if (dfaLeft instanceof DfaVariableValue && dfaRight instanceof DfaVariableValue) {
      if (type == RelationType.LT) {
        if (!applyLessThanRelation(dfaLeft, dfaRight)) return false;
      } else if (type == RelationType.GT) {
        if (!applyLessThanRelation(dfaRight, dfaLeft)) return false;
      } else {
        if (!applyRelation(dfaLeft, dfaRight, isNegated)) return false;
      }
    }
    return applyUnboxedRelation(dfaLeft, dfaRight, isNegated);
  }

  private void checkEphemeral(DfaValue left, DfaValue right) {
    if (getDfType(right) == DfTypes.NULL) {
      DfaNullability nullability = DfaNullability.fromDfType(getDfType(left));
      if (nullability == DfaNullability.UNKNOWN || nullability == DfaNullability.FLUSHED) {
        markEphemeral();
      }
    }
  }

  private boolean equalizeTypesOnGetClass(DfaVariableValue dfaLeft, DfaVariableValue dfaRight) {
    PsiModifierListOwner leftPsi = dfaLeft.getPsiVariable();
    PsiModifierListOwner rightPsi = dfaRight.getPsiVariable();
    if (leftPsi != rightPsi || !(leftPsi instanceof PsiMethod) || !PsiTypesUtil.isGetClass((PsiMethod)leftPsi)) return true;
    DfaVariableValue leftQualifier = dfaLeft.getQualifier();
    DfaVariableValue rightQualifier = dfaRight.getQualifier();
    if (leftQualifier == null || rightQualifier == null || leftQualifier == rightQualifier) return true;
    TypeConstraint leftType = TypeConstraint.fromDfType(getDfType(leftQualifier));
    TypeConstraint rightType = TypeConstraint.fromDfType(getDfType(rightQualifier));
    return meetDfType(leftQualifier, rightType.asDfType()) && meetDfType(rightQualifier, leftType.asDfType());
  }

  @NotNull
  private ThreeState processGetClass(DfaVariableValue variable, PsiType value, boolean negated) {
    EqClass eqClass = getEqClass(variable);
    List<DfaVariableValue> variables = eqClass == null ? Collections.singletonList(variable) : eqClass.asList();
    boolean hasUnprocessed = false;
    for (DfaVariableValue var : variables) {
      PsiModifierListOwner psi = var.getPsiVariable();
      DfaVariableValue qualifier = var.getQualifier();
      if (psi instanceof PsiMethod && PsiTypesUtil.isGetClass((PsiMethod)psi) && qualifier != null) {
        switch (applyGetClassRelation(qualifier, value, negated)) {
          case NO:
            return ThreeState.NO;
          case YES:
            continue;
          case UNSURE:
            break;
        }
      }
      hasUnprocessed = true;
    }
    return hasUnprocessed ? ThreeState.UNSURE : ThreeState.YES;
  }

  @NotNull
  private ThreeState applyGetClassRelation(@NotNull DfaVariableValue qualifier, @NotNull PsiType value, boolean negated) {
    DfaPsiType dfaType = myFactory.createDfaType(value);
    TypeConstraint constraint = TypeConstraint.exact(dfaType);
    PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(value);
    if (psiClass != null && (psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT))) {
      // getClass() result cannot be an interface or an abstract class
      return ThreeState.fromBoolean(negated);
    }
    if (!negated) {
      return ThreeState.fromBoolean(meetDfType(qualifier, constraint.asDfType()));
    }
    TypeConstraint existingConstraint = TypeConstraint.fromDfType(getDfType(qualifier));
    if (existingConstraint.isExact()) {
      return ThreeState.fromBoolean(!existingConstraint.equals(constraint));
    }
    if (dfaType.asConstraint().isExact()) { // final class
      return ThreeState.fromBoolean(
        meetDfType(qualifier, Objects.requireNonNull(TypeConstraint.empty().withNotInstanceofValue(dfaType)).asDfType()));
    }
    return ThreeState.UNSURE;
  }

  private boolean propagateConstant(DfaVariableValue value, DfConstantType<?> constant) {
    if (constant.getValue() instanceof PsiType) {
      if (processGetClass(value, (PsiType)constant.getValue(), false) == ThreeState.NO) {
        return false;
      }
    }
    DfType dfType = constant.tryNegate();
    if (dfType == null) return true;
    EqClass eqClass = getEqClass(value);
    if (eqClass == null) return true;
    for (DistinctPairSet.DistinctPair pair : getDistinctClassPairs().toArray(new DistinctPairSet.DistinctPair[0])) {
      EqClass other = pair.getFirst() == eqClass ? pair.getSecond() : pair.getSecond() == eqClass ? pair.getFirst() : null;
      if (other != null) {
        for (DfaVariableValue var : other.asList()) {
          if (!meetDfType(var, dfType)) return false;
        }
      }
    }
    return true;
  }

  private boolean applyRangeToRelatedValues(DfaValue value, LongRangeSet appliedRange) {
    EqClass eqClass = getEqClass(value);
    if (eqClass == null) return true;
    for (DistinctPairSet.DistinctPair pair : getDistinctClassPairs().toArray(new DistinctPairSet.DistinctPair[0])) {
      if (pair.isOrdered()) {
        if (pair.getFirst() == eqClass) {
          if (!applyRelationRangeToClass(pair.getSecond(), appliedRange, RelationType.GT)) return false;
        } else if(pair.getSecond() == eqClass) {
          if (!applyRelationRangeToClass(pair.getFirst(), appliedRange, RelationType.LT)) return false;
        }
      }
    }
    return true;
  }

  private boolean applyRelationRangeToClass(EqClass eqClass, LongRangeSet range, RelationType relationType) {
    LongRangeSet appliedRange = range.fromRelation(relationType);
    for (DfaVariableValue var : eqClass.asList()) {
      DfType rangeType = DfTypes.rangeClamped(appliedRange, PsiType.LONG.equals(var.getType()));
      if (!meetDfType(var, rangeType)) return false;
    }
    return true;
  }

  private Couple<DfaValue> getSpecialEquivalencePair(DfaVariableValue left, DfaValue right) {
    if (right instanceof DfaVariableValue) return null;
    SpecialField field = SpecialField.fromQualifier(left);
    if (field == null) return null;
    DfaValue leftValue = field.createValue(myFactory, left);
    DfaValue rightValue = field.createValue(myFactory, right);
    return Couple.of(leftValue, rightValue);
  }

  private boolean applySpecialFieldEquivalence(@NotNull DfaValue left, @NotNull DfaValue right) {
    Couple<DfaValue> pair = left instanceof DfaVariableValue ? getSpecialEquivalencePair((DfaVariableValue)left, right) :
                            right instanceof DfaVariableValue ? getSpecialEquivalencePair((DfaVariableValue)right, left) : null;
    if (pair == null || isNaN(pair.getFirst()) || isNaN(pair.getSecond())) return true;
    return applyCondition(pair.getFirst().eq(pair.getSecond()));
  }

  private boolean applyUnboxedRelation(@NotNull DfaValue dfaLeft, DfaValue dfaRight, boolean negated) {
    if (dfaLeft instanceof DfaVariableValue && !TypeConversionUtil.isPrimitiveWrapper(dfaLeft.getType()) ||
        dfaRight instanceof DfaVariableValue && !TypeConversionUtil.isPrimitiveWrapper(dfaRight.getType())) {
      return true;
    }
    PsiType leftType = getPsiType(dfaLeft);
    PsiType rightType = getPsiType(dfaRight);
    if (TypeConversionUtil.isPrimitiveWrapper(leftType) &&
        TypeConversionUtil.isPrimitiveWrapper(rightType) && !leftType.equals(rightType)) {
      // Boxes of different type (e.g. Long and Integer), cannot be equal even if unboxed values are equal
      return negated;
    }

    DfaValue unboxedLeft = SpecialField.UNBOX.createValue(myFactory, dfaLeft);
    DfaValue unboxedRight = SpecialField.UNBOX.createValue(myFactory, dfaRight);
    DfType leftDfType = getDfType(unboxedLeft);
    DfType rightDfType = getDfType(unboxedRight);
    if (leftDfType instanceof DfConstantType && rightDfType instanceof DfConstantType) {
      return leftDfType.equals(rightDfType) != negated;
    }
    if (negated && (PsiType.FLOAT.equals(unboxedLeft.getType()) || PsiType.DOUBLE.equals(unboxedLeft.getType()))) {
      // If floating point wrappers are not equal, unboxed versions could still be equal if they are 0.0 and -0.0
      return true;
    }
    return applyRelation(unboxedLeft, unboxedRight, negated);
  }

  @Nullable
  private PsiType getPsiType(@NotNull DfaValue value) {
    PsiType type = DfaTypeValue.toPsiType(getDfType(value));
    return type == null ? value.getType() : type;
  }

  private static boolean isNaN(final DfaValue dfa) {
    return dfa != null && isNaN(dfa.getDfType());
  }

  private static boolean canBeNaN(@NotNull DfType dfType) {
    return dfType.isSuperType(DfTypes.floatValue(Float.NaN)) || dfType.isSuperType(DfTypes.doubleValue(Double.NaN));
  }

  private static boolean isNaN(DfType type) {
    return type instanceof DfConstantType && DfaUtil.isNaN(((DfConstantType<?>)type).getValue());
  }

  private boolean applyRelation(@NotNull final DfaValue dfaLeft, @NotNull final DfaValue dfaRight, boolean isNegated) {
    if (!(dfaLeft instanceof DfaVariableValue) || !(dfaRight instanceof DfaVariableValue)) return true;
    int c1Index = getOrCreateEqClassIndex((DfaVariableValue)dfaLeft);
    int c2Index = getOrCreateEqClassIndex((DfaVariableValue)dfaRight);
    if (c1Index == c2Index) return !isNegated;

    if (!isNegated) { //Equals
      if (isUnstableValue(dfaLeft) || isUnstableValue(dfaRight)) return true;
      if (!uniteClasses((DfaVariableValue)dfaLeft, (DfaVariableValue)dfaRight)) return false;
    }
    else { // Not Equals
      myDistinctClasses.addUnordered(c1Index, c2Index);
    }
    myCachedNonTrivialEqClasses = null;
    myCachedHash = null;

    return true;
  }

  private boolean applyLessThanRelation(@NotNull final DfaValue dfaLeft, @NotNull final DfaValue dfaRight) {
    if (!(dfaLeft instanceof DfaVariableValue) || !(dfaRight instanceof DfaVariableValue)) return true;
    int c1Index = getOrCreateEqClassIndex((DfaVariableValue)dfaLeft);
    int c2Index = getOrCreateEqClassIndex((DfaVariableValue)dfaRight);
    if (c1Index == c2Index) return false;

    myCachedHash = null;
    return myDistinctClasses.addOrdered(c1Index, c2Index);
  }

  /**
   * Returns true if value represents an "unstable" value. An unstable value is a value of an object type which could be
   * a newly object every time it's accessed. Such value is still useful as its nullability is stable
   *
   * @param value to check.
   * @return true if value might be unstable, false otherwise
   */
  private boolean isUnstableValue(DfaValue value) {
    if (!(value instanceof DfaVariableValue)) return false;
    DfaVariableValue var = (DfaVariableValue)value;
    PsiModifierListOwner owner = var.getPsiVariable();
    if (!(owner instanceof PsiMethod)) return false;
    if (var.getType() instanceof PsiPrimitiveType) return false;
    if (PropertyUtilBase.isSimplePropertyGetter((PsiMethod)owner)) return false;
    if (isNull(var)) return false;
    return true;
  }

  @Nullable
  private static RelationType getFloatingConstantRelation(DfType leftType, DfType rightType) {
    Number value1 = DfConstantType.getConstantOfType(leftType, Number.class);
    Number value2 = DfConstantType.getConstantOfType(rightType, Number.class);
    if (value1 == null || value2 == null) return null;
    double double1 = value1.doubleValue();
    double double2 = value2.doubleValue();
    if (double1 == 0.0 && double2 == 0.0) return RelationType.EQ;
    int cmp = Double.compare(double1, double2);
    return cmp == 0 ? RelationType.EQ : cmp < 0 ? RelationType.LT : RelationType.GT;
  }

  @Override
  public boolean checkNotNullable(@NotNull DfaValue value) {
    DfaNullability nullability = DfaNullability.fromDfType(getDfType(value));
    return nullability != DfaNullability.NULL && nullability != DfaNullability.NULLABLE;
  }

  @Nullable
  public LongRangeSet getBinOpRange(DfaBinOpValue binOp) {
    LongRangeSet left = DfLongType.extractRange(getDfType(binOp.getLeft()));
    LongRangeSet right = DfLongType.extractRange(getDfType(binOp.getRight()));
    boolean isLong = PsiType.LONG.equals(binOp.getType());
    LongRangeSet result = left.binOpFromToken(binOp.getTokenType(), right, isLong);
    if (result != null && binOp.getOperation() == DfaBinOpValue.BinOp.MINUS) {
      RelationType rel = getRelation(binOp.getLeft(), binOp.getRight());
      if (rel == RelationType.NE) {
        return result.without(0);
      }
      if (!left.subtractionMayOverflow(right, isLong)) {
        if (rel == RelationType.GT) {
          return result.intersect(LongRangeSet.range(1, isLong ? Long.MAX_VALUE : Integer.MAX_VALUE));
        }
        if (rel == RelationType.LT) {
          return result.intersect(LongRangeSet.range(isLong ? Long.MIN_VALUE : Integer.MIN_VALUE, -1));
        }
      }
    }
    if (binOp.getOperation() == DfaBinOpValue.BinOp.PLUS && areEqual(binOp.getLeft(), binOp.getRight())) {
      return LongRangeSet.point(2).mul(left, isLong);
    }
    return result;
  }

  @NotNull
  @Override
  public DfType getUnboxedDfType(@NotNull DfaValue value) {
    if (value instanceof DfaBoxedValue) {
      return getDfType(((DfaBoxedValue)value).getWrappedValue());
    }
    if (value instanceof DfaVariableValue && TypeConversionUtil.isPrimitiveWrapper(value.getType())) {
      return getDfType(SpecialField.UNBOX.createValue(myFactory, value));
    }
    if (value instanceof DfaTypeValue) {
      DfReferenceType refType = ObjectUtils.tryCast(value.getDfType(), DfReferenceType.class);
      if (refType != null && refType.getSpecialField() == SpecialField.UNBOX) {
        return refType.getSpecialFieldType();
      }
    }
    return getDfType(value);
  }

  @NotNull
  @Override
  public DfType getDfType(@NotNull DfaValue value) {
    if (value instanceof DfaBinOpValue) {
      LongRangeSet range = getBinOpRange((DfaBinOpValue)value);
      if (range == null) range = LongRangeSet.all();
      return ((DfaBinOpValue)value).getDfType().meetRange(range);
    }
    if (value instanceof DfaVariableValue) {
      return getVariableState((DfaVariableValue)value).myDfType;
    }
    return value.getDfType();
  }

  void setVariableState(@NotNull DfaVariableValue dfaVar, @NotNull DfaVariableState state) {
    dfaVar = canonicalize(dfaVar);
    if (state.equals(getDefaultState(dfaVar))) {
      myVariableStates.remove(dfaVar);
    } else {
      myVariableStates.put(dfaVar, state);
    }
    myCachedHash = null;
  }

  protected void updateEquivalentVariables(DfaVariableValue dfaVar, DfaVariableState state) {
    EqClass eqClass = getEqClass(dfaVar);
    if (eqClass != null) {
      for (DfaVariableValue value : eqClass.asList()) {
        if (value != dfaVar) {
          setVariableState(value, state);
        }
      }
    }
  }

  @NotNull
  private DfaValue canonicalize(@NotNull DfaValue value) {
    if (value instanceof DfaVariableValue) {
      return canonicalize((DfaVariableValue)value);
    }
    if (value instanceof DfaBoxedValue) {
      DfaBoxedValue boxedValue = (DfaBoxedValue)value;
      DfaValue canonicalized = canonicalize(boxedValue.getWrappedValue());
      return Objects.requireNonNull(myFactory.getBoxedFactory().createBoxed(canonicalized, boxedValue.getType()));
    }
    return value;
  }

  @NotNull
  private DfaVariableValue canonicalize(DfaVariableValue var) {
    DfaVariableValue qualifier = var.getQualifier();
    if (qualifier != null) {
      Integer index = myIdToEqClassesIndices.get(qualifier.getID());
      if (index == null) {
        qualifier = canonicalize(qualifier);
        index = myIdToEqClassesIndices.get(qualifier.getID());
        if (index == null) {
          return var.withQualifier(qualifier);
        }
      }

      return var.withQualifier(Objects.requireNonNull(myEqClasses.get(index).getCanonicalVariable()));
    }
    return var;
  }

  private DfaVariableState getExistingVariableState(DfaVariableValue var) {
    DfaVariableState state = myVariableStates.get(var);
    if (state != null) {
      return state;
    }
    DfaVariableValue canonicalized = canonicalize(var);
    return canonicalized == var ? null : myVariableStates.get(canonicalized);
  }

  @NotNull
  DfaVariableState getVariableState(DfaVariableValue dfaVar) {
    DfaVariableState state = getExistingVariableState(dfaVar);
    return state != null ? state : getDefaultState(dfaVar);
  }

  @NotNull
  private DfaVariableState getDefaultState(DfaVariableValue dfaVar) {
    return myDefaultVariableStates.computeIfAbsent(dfaVar, this::createVariableState);
  }

  void forVariableStates(BiConsumer<? super DfaVariableValue, ? super DfaVariableState> consumer) {
    myVariableStates.forEach(consumer);
  }

  @NotNull
  protected DfaVariableState createVariableState(@NotNull DfaVariableValue var) {
    return new DfaVariableState(var);
  }

  @Override
  public void flushFields() {
    Set<DfaVariableValue> vars = new LinkedHashSet<>(getChangedVariables());
    for (EqClass aClass : myEqClasses) {
      if (aClass != null) {
        ContainerUtil.addAll(vars, aClass);
      }
    }
    for (DfaVariableValue value : vars) {
      if (!value.isFlushableByCalls()) continue;
      DfaVariableValue qualifier = value.getQualifier();
      if (qualifier != null) {
        DfReferenceType dfType = ObjectUtils.tryCast(getDfType(qualifier), DfReferenceType.class);
        if (dfType != null && (dfType.getMutability() == Mutability.UNMODIFIABLE || dfType.isLocal())) continue;
      }
      doFlush(value, shouldMarkFlushed(value));
    }
    myStack.replaceAll(val -> {
      DfType type = val.getDfType();
      if (type instanceof DfReferenceType) {
        SpecialField field = ((DfReferenceType)type).getSpecialField();
        if (field != null && !field.isStable() && ((DfReferenceType)type).getMutability() != Mutability.UNMODIFIABLE) {
          return myFactory.fromDfType(((DfReferenceType)type).dropSpecialField());
        }
      }
      return val;
    });
  }

  private boolean shouldMarkFlushed(@NotNull DfaVariableValue value) {
    if (value.getInherentNullability() != Nullability.NULLABLE) return false;
    return DfaNullability.fromDfType(getVariableState(value).myDfType) == DfaNullability.FLUSHED || isNull(value) || isNotNull(value);
  }

  @NotNull
  Set<DfaVariableValue> getChangedVariables() {
    return myVariableStates.keySet();
  }

  @Override
  public void flushVariable(@NotNull final DfaVariableValue variable) {
    flushVariable(variable, false);
  }

  protected void flushVariable(@NotNull final DfaVariableValue variable, boolean shouldMarkFlushed) {
    EqClass eqClass = variable.getDependentVariables().isEmpty() ? null : getEqClass(variable);
    DfaVariableValue newCanonical =
      eqClass == null ? null : StreamEx.of(eqClass.iterator()).without(variable).min(EqClass.CANONICAL_VARIABLE_COMPARATOR)
        .filter(candidate -> !candidate.dependsOn(variable))
        .orElse(null);
    myStack.replaceAll(value -> handleStackValueOnVariableFlush(value, variable, newCanonical));

    doFlush(variable, shouldMarkFlushed);
    flushDependencies(variable);
    myCachedHash = null;
  }

  void flushDependencies(@NotNull DfaVariableValue variable) {
    for (DfaVariableValue dependent : variable.getDependentVariables().toArray(new DfaVariableValue[0])) {
      doFlush(dependent, false);
    }
  }

  private void flushQualifiedMethods(@NotNull DfaVariableValue variable) {
    PsiModifierListOwner psiVariable = variable.getPsiVariable();
    DfaVariableValue qualifier = variable.getQualifier();
    if (psiVariable instanceof PsiField && qualifier != null) {
      // Flush method results on field write
      List<DfaVariableValue> toFlush =
        ContainerUtil.filter(qualifier.getDependentVariables(), DfaVariableValue::containsCalls);
      toFlush.forEach(val -> doFlush(val, shouldMarkFlushed(val)));
    }
  }

  void doFlush(@NotNull DfaVariableValue var, boolean markFlushed) {
    if(isNull(var)) {
      myStack.replaceAll(val -> val == var ? myFactory.getNull() : val);
    }

    removeEquivalence(var);
    myVariableStates.remove(var);
    if (markFlushed) {
      setVariableState(var, getVariableState(var).withNullability(DfaNullability.FLUSHED));
    }
    myCachedHash = null;
  }

  void removeEquivalence(DfaVariableValue var) {
    int varID = var.getID();
    Integer varClassIndex = myIdToEqClassesIndices.get(varID);
    if (varClassIndex == null) {
      var = canonicalize(var);
      varID = var.getID();
      varClassIndex = myIdToEqClassesIndices.get(varID);
      if (varClassIndex == null) return;
    }

    EqClass varClass = myEqClasses.get(varClassIndex);

    varClass = new EqClass(varClass);
    DfaVariableValue previousCanonical = varClass.getCanonicalVariable();
    myEqClasses.set(varClassIndex, varClass);
    varClass.removeValue(varID);
    myIdToEqClassesIndices.remove(varID);
    checkInvariants();

    if (varClass.isEmpty()) {
      myEqClasses.set(varClassIndex, null);

      for (Iterator<DistinctPairSet.DistinctPair> iterator = myDistinctClasses.iterator(); iterator.hasNext(); ) {
        DistinctPairSet.DistinctPair pair = iterator.next();
        if (pair.getOtherClass(varClassIndex) != null) {
          iterator.remove();
        }
      }
    }
    else {
      DfaVariableValue newCanonical = varClass.getCanonicalVariable();
      if (newCanonical != null && previousCanonical != null && previousCanonical != newCanonical &&
          (ControlFlowAnalyzer.isTempVariable(previousCanonical) && !newCanonical.dependsOn(previousCanonical) ||
           newCanonical.getDepth() <= previousCanonical.getDepth())) {
        // Do not transfer to deeper qualifier. E.g. if we have two classes like (a, b.c) (a.d, e),
        // and flushing `a`, we do not convert `a.d` to `b.c.d`. Otherwise infinite qualifier explosion is possible.
        boolean successfullyConverted = convertQualifiers(previousCanonical, newCanonical);
        assert successfullyConverted;
      }
    }

    myCachedNonTrivialEqClasses = null;
    myCachedHash = null;
  }

  /**
   * @return a mergeability key. If two states return the same key, then states could be merged via {@link #merge(DfaMemoryStateImpl)}.
   */
  Object getMergeabilityKey() {
    /*
      States are mergeable if:
      - Ephemeral flag is the same
      - Stack depth is the same
      - All DfaControlTransferValues in the stack are the same (otherwise finally blocks may not complete successfully)
      - Top-of-stack value is the same (otherwise we may prematurely merge true/false on TOS right before jump which is very undesired)
     */
    return StreamEx.of(myStack).<Object>mapLastOrElse(val -> ObjectUtils.tryCast(val, DfaControlTransferValue.class),
                                                      Function.identity())
      .append(isEphemeral()).toImmutableList();
  }

  /**
   * Updates this DfaMemoryState so that it becomes a minimal superstate which covers the other state as well
   *
   * @param other other state which has equal {@link #getMergeabilityKey()}
   */
  void merge(DfaMemoryStateImpl other) {
    assert other.isEphemeral() == isEphemeral();
    assert other.myStack.size() == myStack.size();
    ProgressManager.checkCanceled();
    retainEquivalences(other);
    mergeDistinctPairs(other);
    mergeVariableStates(other);
    mergeStacks(other);
    myCachedHash = null;
    myCachedNonTrivialEqClasses = null;
    afterMerge(other);
  }

  /**
   * Custom logic to be implemented by subclasses
   * @param other
   */
  protected void afterMerge(DfaMemoryStateImpl other) {

  }

  private void mergeStacks(DfaMemoryStateImpl other) {
    List<DfaValue> values = StreamEx.zip(myStack, other.myStack, DfaValue::unite).toList();
    myStack.clear();
    values.forEach(myStack::push);
  }

  private void mergeDistinctPairs(DfaMemoryStateImpl other) {
    ArrayList<DistinctPairSet.DistinctPair> pairs = new ArrayList<>(myDistinctClasses);
    for (DistinctPairSet.DistinctPair pair : pairs) {
      EqClass first = pair.getFirst();
      EqClass second = pair.getSecond();
      RelationType relation = other.getRelation(myFactory.getValue(first.get(0)), myFactory.getValue(second.get(0)));
      if (relation == null || relation == RelationType.EQ) {
        myDistinctClasses.remove(pair);
      }
      else if (pair.isOrdered() && relation != RelationType.LT) {
        myDistinctClasses.dropOrder(pair);
      }
    }
  }

  private void mergeVariableStates(DfaMemoryStateImpl other) {
    Set<DfaVariableValue> vars = StreamEx.of(myVariableStates, other.myVariableStates).toFlatCollection(Map::keySet, HashSet::new);
    for (DfaVariableValue var : vars) {
      DfaVariableState state = getVariableState(var);
      DfaVariableState otherState = other.getVariableState(var);
      DfType result = state.myDfType.join(otherState.myDfType);
      Nullability nullability = state.getNullability();
      Nullability otherNullability = otherState.getNullability();
      if (nullability != otherNullability && (nullability == Nullability.NULLABLE || otherNullability == Nullability.NULLABLE)) {
        // When merging nullable with something we cannot warn about nullability violation anymore
        // because we lose the information about coherent state, thus noise warnings could be produced
        result = ((DfReferenceType)result).dropNullability().meet(DfaNullability.FLUSHED.asDfType());
      }
      setVariableState(var, state.createCopy(result));
    }
  }

  private void retainEquivalences(DfaMemoryStateImpl other) {
    boolean needRestart = true;
    while (needRestart) {
      ProgressManager.checkCanceled();
      needRestart = false;
      for (EqClass eqClass : new ArrayList<>(myEqClasses)) {
        if (eqClass != null && retainEquivalences(eqClass, other)) {
          needRestart = true;
          break;
        }
      }
    }
  }

  /**
   * Retain only those equivalences from given class which are present in other memory state
   *
   * @param eqClass an equivalence class to process
   * @param other   other memory state. If it does not contain all the equivalences from the eqClass, the eqClass will
   *                be split to retain only remaining equivalences
   * @return true if not only given class, but also some other classes were updated due to canonicalization
   */
  private boolean retainEquivalences(EqClass eqClass, DfaMemoryStateImpl other) {
    if (eqClass.size() <= 1) return false;
    List<EqClass> groups = splitEqClass(eqClass, other);
    if (groups.size() == 1) return false;

    TIntArrayList addedClasses = new TIntArrayList();
    int origIndex = myIdToEqClassesIndices.get(eqClass.get(0));
    for (EqClass group : groups) {
      addedClasses.add(storeClass(group));
    }
    int[] addedClassesArray = addedClasses.toNativeArray();
    myDistinctClasses.splitClass(origIndex, addedClassesArray);
    myEqClasses.set(origIndex, null);

    DfaVariableValue from = eqClass.getCanonicalVariable();
    boolean otherClassChanged = false;
    if (from != null && !from.getDependentVariables().isEmpty()) {
      List<DfaVariableValue> vars = new ArrayList<>(myVariableStates.keySet());
      for (int classIndex : addedClassesArray) {
        DfaVariableValue to = myEqClasses.get(classIndex).getCanonicalVariable();
        if (to == null || to == from || to.getDepth() > from.getDepth()) continue;

        for (DfaVariableValue var : vars) {
          DfaVariableValue target = replaceQualifier(var, from, to);
          if (target != var) {
            setVariableState(target, getVariableState(var));
          }
        }
        for (int valueId : myIdToEqClassesIndices.keys()) {
          DfaValue value = myFactory.getValue(valueId);
          DfaVariableValue var = ObjectUtils.tryCast(value, DfaVariableValue.class);
          if (var == null || var.getQualifier() != from) continue;
          DfaVariableValue target = var.withQualifier(to);
          boolean united = uniteClasses(var, target);
          assert united;
          otherClassChanged = true;
        }
      }
    }
    checkInvariants();
    return otherClassChanged;
  }

  /**
   * Splits given EqClass to several classes removing equivalences absent in other state
   *
   * @param eqClass an equivalence class to split
   * @param other   other memory state; only equivalences present in that state should be preserved
   * @return list of created classes (the original class remains unchanged). Trivial classes are also included,
   * thus sum of resulting class sizes is equal to the original class size
   */
  @NotNull
  private List<EqClass> splitEqClass(EqClass eqClass, DfaMemoryStateImpl other) {
    TIntObjectHashMap<EqClass> groupsInClasses = new TIntObjectHashMap<>();
    List<EqClass> groups = new ArrayList<>();
    for (DfaVariableValue value : eqClass.asList()) {
      int otherClass = other.getEqClassIndex(value);
      EqClass list;
      if (otherClass == -1) {
        list = new EqClass(myFactory);
        groups.add(list);
      }
      else {
        list = groupsInClasses.get(otherClass);
        if (list == null) {
          list = new EqClass(myFactory);
          groupsInClasses.put(otherClass, list);
        }
      }
      list.add(value.getID());
    }
    groupsInClasses.forEachValue(groups::add);
    return groups;
  }

  private class MyIdMap extends TIntObjectHashMap<Integer> {
    @Override
    public String toString() {
      final StringBuilder s = new StringBuilder("{");
      forEachEntry(new TIntObjectProcedure<Integer>() {
        @Override
        public boolean execute(int id, Integer index) {
          DfaValue value = myFactory.getValue(id);
          s.append(value).append(" -> ").append(index).append(", ");
          return true;
        }
      });
      s.append("}");
      return s.toString();
    }
  }
}
