package com.genx.ai.photo.editpdf

import com.tom_roush.pdfbox.contentstream.operator.OperatorProcessor
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class CheckOpsTest {
    @Test
    fun findTm() {
        val f = File("C:/temp/pdfbox_aar_2/classes.jar")
        if (!f.exists()) { println("NO FILE"); return }
        val zip = ZipFile(f)
        for (entry in zip.entries()) {
            if (entry.name.endsWith(".class") && entry.name.contains("operator")) {
                val className = entry.name.replace("/", ".").removeSuffix(".class")
                try {
                    val clazz = Class.forName(className)
                    if (OperatorProcessor::class.java.isAssignableFrom(clazz)) {
                        val inst = clazz.newInstance() as OperatorProcessor
                        if (inst.name == "Tm") {
                            println("FOUND Tm in: $className")
                        }
                    }
                } catch(e: Exception) {}
            }
        }
    }
}
