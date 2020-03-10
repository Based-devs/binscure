package dev.binclub.binscure

import org.objectweb.asm.tree.ClassNode
import java.security.SecureRandom

/**
 * @author cookiedragon234 20/Jan/2020
 */
interface IClassProcessor {
	val random: SecureRandom
		get() = CObfuscator.random
	
	fun process(classes: MutableCollection<ClassNode>, passThrough: MutableMap<String, ByteArray>)
}