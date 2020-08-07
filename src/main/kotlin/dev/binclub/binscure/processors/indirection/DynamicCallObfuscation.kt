package dev.binclub.binscure.processors.indirection

import dev.binclub.binscure.CObfuscator
import dev.binclub.binscure.IClassProcessor
import dev.binclub.binscure.classpath.ClassPath
import dev.binclub.binscure.configuration.ConfigurationManager.rootConfig
import dev.binclub.binscure.processors.renaming.impl.ClassRenamer
import dev.binclub.binscure.utils.*
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*
import kotlin.collections.ArrayList

/**
 * @author cookiedragon234 22/Jan/2020
 */
object DynamicCallObfuscation: IClassProcessor {
	private val targetOps = arrayOf(INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE)
	
	private var isInit: Boolean = false
	private val decryptNode: ClassNode by lazy {
		isInit = true
		ClassNode().apply {
			access = ACC_PUBLIC + ACC_FINAL
			version = V1_8
			name = ClassRenamer.namer.uniqueRandomString()
			signature = null
			superName = "java/lang/Object"
		}
	}
	
	private val stringDecryptMethod: MethodNode by lazy {
		MethodNode(
			ACC_PRIVATE + ACC_STATIC,
			"a",
			"(Ljava/lang/String;)Ljava/lang/String;",
			null,
			null
		).apply {
			generateDecryptorMethod(decryptNode, this)
			decryptNode.methods.add(this)
		}
	}
	
	private val bootStrapMethod: MethodNode by lazy {
		MethodNode(
			ACC_PUBLIC + ACC_STATIC,
			"b",
			"(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
			null,
			null
		).apply {
			generateBootstrapMethod(decryptNode.name, stringDecryptMethod, this)
			decryptNode.methods.add(this)
		}
	}
	
	private val handler: Handle by lazy {
		Handle(H_INVOKESTATIC, decryptNode.name, bootStrapMethod.name, bootStrapMethod.desc, false)
	}
	override val progressDescription: String
		get() = "Transforming method calls to dynamic invokes"
	override val config = rootConfig.indirection
	
	override fun process(classes: MutableCollection<ClassNode>, passThrough: MutableMap<String, ByteArray>) {
		if (!config.enabled) {
			return
		}
		
		for (classNode in ArrayList(classes)) {
			if (isExcluded(classNode))
				continue
			if (!classNode.versionAtLeast(V1_7))
				continue
			
			for (method in classNode.methods) {
				if (isExcluded(classNode, method) || CObfuscator.noMethodInsns(method))
					continue
				
				method.instructions = InsnList().apply {
					for (insn in method.instructions) {
						if (insn is MethodInsnNode) {
							if (targetOps.contains(insn.opcode)) {
								//if (insn.owner.startsWith('[')) continue
								
								var newDesc = insn.desc
								if (insn.opcode != INVOKESTATIC) {
									newDesc = if ((insn.owner.startsWith('L') || insn.owner.startsWith("["))) {
										newDesc.replaceFirst("(", "(${insn.owner}")
									} else {
										newDesc.replaceFirst("(", "(L${insn.owner};")
									}
								}
								val returnType = Type.getReturnType(newDesc)
								val newReturnType = downCastType(returnType)
								
								val args = Type.getArgumentTypes(newDesc)
								
								// Downcast types to java/lang/Object
								for (i in args.indices) {
									if (insn.opcode != INVOKESTATIC && i == 0) continue
									args[i] = downCastType(args[i])
								}
								
								//newDesc = Type.getMethodDescriptor(downCastType(returnType), *args)
								newDesc = Type.getMethodDescriptor(newReturnType, *args)
								
								val paramOwner = insn.owner.replace('/', '.')
								
								val indyNode = InvokeDynamicInsnNode(
									"i",
									newDesc,
									handler,
									insn.opcode,
									encryptName(classNode, method, paramOwner),
									encryptName(classNode, method, insn.name),
									encryptName(classNode, method, insn.desc)
								)
								add(indyNode)
								
								// Cast return type to expected type (since we downcasted to Object earlier)
								var checkCast: TypeInsnNode? = null
								if (returnType.sort == Type.ARRAY) {
									checkCast = (TypeInsnNode(CHECKCAST, returnType.internalName))
								} else if (returnType.sort == Type.OBJECT) {
									if (insn.next is MethodInsnNode) {
										val next = insn.next as MethodInsnNode
										val params = Type.getArgumentTypes(next.desc)
										if (params.isEmpty()) {
											if (insn.next.opcode == INVOKEVIRTUAL) {
												checkCast = TypeInsnNode(CHECKCAST, next.owner)
											}
										} else {
											checkCast = TypeInsnNode(CHECKCAST, params.last().internalName)
										}
									} else if (arrayOf(POP, POP2, RETURN, IFNONNULL, IFNULL).contains(insn.next?.opcode)) {
										// Ignore
									} else {
										checkCast = (TypeInsnNode(CHECKCAST, returnType.internalName))
									}
								}
								
								val debug = false//insn.owner.contains("TestEnum")
								
								if (debug) {
									println("\r-----")
									
									println("Transforming: ${insn.owner}.${insn.name}.${insn.desc}")
									println("Into: $newDesc")
								}
								
								if (checkCast != null && insn.next?.opcode != CHECKCAST) {
									if (checkCast.desc != Any::class.internalName) {
										
										if (debug) {
											println("Checkcasting to ${checkCast.desc}")
										}
										
										add(checkCast)
									}
								}
								continue
							}
						}
						add(insn)
					}
				}
			}
		}
		
		if (isInit) {
			ClassPath.classes[decryptNode.name] = decryptNode
			ClassPath.classPath[decryptNode.name] = decryptNode
		}
	}
	
	private fun encryptName(classNode: ClassNode, methodNode: MethodNode, originalStr: String): String {
		val classHash = classNode.name.replace('/', '.').hashCode()
		val methodHash = methodNode.name.replace('/', '.').hashCode()
		
		val original = originalStr.toCharArray()
		val new = CharArray(original.size)
		
		for (i in original.indices) {
			val char = original[i]
			new[i] = when (i % 5) {
				0 -> char xor 2
				1 -> char xor classHash
				2 -> char xor methodHash
				3 -> char xor (classHash + methodHash)
				4 -> char xor i
				else -> throw IllegalStateException("Illegal ${i % 6}")
			}
		}
		return String(new)
	}
	
	data class MethodCall(val classNode: ClassNode, val methodNode: MethodNode, val insnNode: MethodInsnNode)
}
