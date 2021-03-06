// Copyright (c) 2003-2014, Jodd Team (jodd.org). All Rights Reserved.

package jodd.proxetta.asm;

import jodd.asm.AsmUtil;
import jodd.asm.MethodAdapter;
import jodd.proxetta.InvokeAspect;
import jodd.proxetta.InvokeInfo;
import jodd.proxetta.InvokeReplacer;
import jodd.proxetta.MethodInfo;
import jodd.proxetta.ProxettaException;
import jodd.util.StringPool;
import jodd.asm5.Label;
import jodd.asm5.MethodVisitor;
import jodd.asm5.Type;

import static jodd.proxetta.asm.ProxettaAsmUtil.INIT;
import static jodd.asm5.Opcodes.ALOAD;
import static jodd.asm5.Opcodes.DUP;
import static jodd.asm5.Opcodes.INVOKEINTERFACE;
import static jodd.asm5.Opcodes.INVOKESPECIAL;
import static jodd.asm5.Opcodes.INVOKESTATIC;
import static jodd.asm5.Opcodes.INVOKEVIRTUAL;
import static jodd.asm5.Opcodes.NEW;

/**
 * Invocation replacer method adapter.
 */
public class InvokeReplacerMethodAdapter extends MethodAdapter {

	protected final WorkData wd;
	protected final MethodInfo methodInfo;
	protected final InvokeAspect[] aspects;

	public InvokeReplacerMethodAdapter(MethodVisitor mv, MethodInfo methodInfo, WorkData wd, InvokeAspect[] aspects) {
		super(mv);
		this.wd = wd;
		this.aspects = aspects;
		this.methodInfo = methodInfo;
	}

	/**
	 * Detects super ctor invocation.
	 */
	protected boolean firstSuperCtorInitCalled;

	/**
	 * New object creation matched.
	 */
	protected InvokeReplacer newInvokeReplacer;

	/**
	 * Invoked on INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE or INVOKEDYNAMIC.
	 */
	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc) {

		// replace NEW.<init>
		if ((newInvokeReplacer != null) && (opcode == INVOKESPECIAL)) {
			String exOwner = owner;
			owner = newInvokeReplacer.getOwner();
			name = newInvokeReplacer.getMethodName();
			desc = changeReturnType(desc, 'L' + exOwner + ';');
			super.visitMethodInsn(INVOKESTATIC, owner, name, desc);
			newInvokeReplacer = null;
			return;
		}


		InvokeInfo invokeInfo = new InvokeInfo(owner, name, desc);

		// [*]
		// creating FooClone.<init>; inside the FOO constructor
		// replace the very first invokespecial <init> call (SUB.<init>)
		// to targets subclass with target (FOO.<init>).
		if (methodInfo.getMethodName().equals(INIT)) {
			if (
					(firstSuperCtorInitCalled == false) &&
							(opcode == INVOKESPECIAL) &&
							name.equals(INIT) &&
							owner.equals(wd.nextSupername)
					) {
				firstSuperCtorInitCalled = true;
				owner = wd.superReference;
				super.visitMethodInsn(opcode, owner, name, desc);
				return;
			}
		}

		// detection of super calls
		if ((opcode == INVOKESPECIAL) && (owner.equals(wd.nextSupername) && (name.equals(INIT) == false))) {
			throw new ProxettaException("Super call detected in class " + methodInfo.getClassname() + " method: " + methodInfo.getSignature() +
				"\nProxetta can't handle super calls due to VM limitations.");
		}


		InvokeReplacer ir = null;

		// find first matching aspect
		for (InvokeAspect aspect : aspects) {
			ir = aspect.pointcut(invokeInfo);
			if (ir != null) {
				break;
			}
		}

		if (ir == null) {
			super.visitMethodInsn(opcode, owner, name, desc);
			return;
		}

		wd.proxyApplied = true;

		String exOwner = owner;
		owner = ir.getOwner();
		name = ir.getMethodName();

		switch (opcode) {
			case INVOKEINTERFACE:
				desc = prependArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_OBJECT);
				break;
			case INVOKEVIRTUAL:
				desc = prependArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_OBJECT);
				break;
			case INVOKESTATIC:
				break;
			default:
				throw new ProxettaException("Unsupported opcode: " + opcode);
		}

		// additional arguments
		if (ir.isPassOwnerName()) {
			desc = appendArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_STRING);
			super.visitLdcInsn(exOwner);
		}
		if (ir.isPassMethodName()) {
			desc = appendArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_STRING);
			super.visitLdcInsn(methodInfo.getMethodName());
		}
		if (ir.isPassMethodSignature()) {
			desc = appendArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_STRING);
			super.visitLdcInsn(methodInfo.getSignature());
		}
		if (ir.isPassTargetClass()) {
			desc = appendArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_CLASS);
			super.mv.visitLdcInsn(Type.getType('L' + wd.superReference + ';'));
		}
		if (ir.isPassThis()) {
			desc = appendArgument(desc, AsmUtil.L_SIGNATURE_JAVA_LANG_OBJECT);
			super.mv.visitVarInsn(ALOAD, 0);
		}

		super.visitMethodInsn(INVOKESTATIC, owner, name, desc);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		// [*]
		// Fix all Foo.<field> to FooClone.<field>
		if (owner.equals(wd.superReference)) {
			owner = wd.thisReference;
		}
		super.visitFieldInsn(opcode, owner, name, desc);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		if (opcode == NEW) {
			InvokeInfo invokeInfo = new InvokeInfo(type, INIT, StringPool.EMPTY);
			for (InvokeAspect aspect : aspects) {
				InvokeReplacer ir = aspect.pointcut(invokeInfo);
				if (ir != null) {
					newInvokeReplacer = ir;

					// new pointcut found, skip the new instruction and the following dup.
					// and then go to the invokespecial
					return;
				}
			}
		}
		super.visitTypeInsn(opcode, type);
	}

	@Override
	public void visitInsn(int opcode) {
		if ((newInvokeReplacer != null) && (opcode == DUP)) {
			return;	// skip dup after new
		}
		super.visitInsn(opcode);
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
	}

	@Override
	public void visitLineNumber(int line, Label start) {
	}


	// ---------------------------------------------------------------- util

	/**
	 * Appends argument to the existing description.
	 */
	protected static String appendArgument(String desc, String type) {
		int ndx = desc.indexOf(')');
		return desc.substring(0, ndx) + type + desc.substring(ndx);
	}

	/**
	 * Prepends argument to the existing description.
	 */
	protected static String prependArgument(String desc, String type) {
		int ndx = desc.indexOf('(');
		ndx++;
		return desc.substring(0, ndx) + type + desc.substring(ndx);
	}

	/**
	 * Changes return type.
	 */
	protected static String changeReturnType(String desc, String type) {
		int ndx = desc.indexOf(')');
		return desc.substring(0, ndx + 1) + type;
	} 

}
