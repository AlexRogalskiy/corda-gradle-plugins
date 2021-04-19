@file:JvmName("XmlSchema")
package net.corda.plugins.cpk.xml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.fail
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.InputStream
import java.util.Base64
import java.util.Collections.emptyIterator
import java.util.Collections.unmodifiableList
import javax.xml.parsers.DocumentBuilderFactory

private class ElementIterator(private val nodes: NodeList) : Iterator<Element> {
    private var index = 0

    private val next: Element? get() {
        while (index < nodes.length) {
            val element = nodes.item(index) as? Element
            if (element != null) {
                return element
            }
            ++index
        }
        return null
    }

    override fun hasNext(): Boolean {
        return next != null
    }

    override fun next(): Element {
        return (next ?: throw NoSuchElementException()).also {
            ++index
        }
    }
}

val Node.childElements: Iterator<Element> get() {
    return if (hasChildNodes()) {
        ElementIterator(childNodes)
    } else {
        emptyIterator()
    }
}

interface AbstractBuilder<T> {
    fun build(): T
}

class HashValue(val value: ByteArray, val algorithm: String) {
    val isSHA256: Boolean
        get() = algorithm == "SHA-256" && value.size == 32

    override fun toString(): String {
        return Base64.getEncoder().encodeToString(value)
    }

    class Builder(private val element: Element) : AbstractBuilder<HashValue> {
        override fun build(): HashValue {
            val algorithm = element.getAttribute("algorithm")
            val value = Base64.getDecoder().decode(element.textContent)
            return HashValue(
                value = value ?: fail("hash.value missing"),
                algorithm = algorithm ?: fail("hash.algorithm missing")
            )
        }
    }
}

class SignersBuilder(private val node: Node) : AbstractBuilder<List<HashValue>> {
    private val signers = mutableListOf<HashValue>()

    override fun build(): List<HashValue> {
        for (childElement in node.childElements) {
            when (val tagName = childElement.tagName) {
                "signer" -> signers.add(HashValue.Builder(childElement).build())
                else -> fail("Unknown XML element <$tagName>")
            }
        }
        return unmodifiableList(signers)
    }
}

class CPKDependency(
    val name: String,
    val version: String,
    val signers: List<HashValue>
) {
    override fun toString(): String {
        return """<cpkDependency>
    <name>$name</name>
    <version>$version</version>
    ${formatSigners()}
</cpkDependency>"""
    }

    private fun formatSigners(): String {
        return if (signers.isEmpty()) {
            "<signers/>"
        } else {
            signers.joinToString(
                prefix = "<signers>",
                postfix = "</signers>",
                transform = ::formatSigner
            )
        }
    }

    private fun formatSigner(signer: HashValue): String {
        return """<signer algorithm="${signer.algorithm}">$signer</signer>"""
    }

    class Builder(private val node: Node) : AbstractBuilder<CPKDependency> {
        private var name: String? = null
        private var version: String? = null
        private var signers: List<HashValue>? = null

        override fun build(): CPKDependency {
            for (childElement in node.childElements) {
                when (val tagName = childElement.tagName) {
                    "name" -> name = childElement.textContent
                    "version" -> version = childElement.textContent
                    "signers" -> signers = SignersBuilder(childElement).build()
                    else -> fail("Unknown XML element <$tagName>")
                }
            }
            return CPKDependency(
                name = name ?: fail("cpkDependency.name missing"),
                version = version ?: fail("cpkDependency.version missing"),
                signers = signers ?: fail("cpkDependency.signers missing")
            )
        }
    }
}

class CPKDependenciesBuilder(private val node: Node) : AbstractBuilder<List<CPKDependency>> {
    private val dependencies = mutableListOf<CPKDependency>()

    override fun build(): List<CPKDependency> {
        for (childElement in node.childElements) {
            when (val tagName = childElement.tagName) {
                "cpkDependency" ->
                    dependencies.add(CPKDependency.Builder(childElement).build())
                else -> fail("Unknown XML element <$tagName>")
            }
        }
        return unmodifiableList(dependencies)
    }
}

class DependencyConstraint(val fileName: String, val hash: HashValue) {
    override fun toString(): String {
        return """<dependencyConstraint>
    <fileName>$fileName</fileName>
    <hash algorithm="${hash.algorithm}">$hash</hash>
</dependencyConstraint>"""
    }

    class Builder(private val node: Node) : AbstractBuilder<DependencyConstraint> {
        private var fileName: String? = null
        private var hash: HashValue? = null

        override fun build(): DependencyConstraint {
            for (childElement in node.childElements) {
                when (val tagName = childElement.tagName) {
                    "fileName" -> fileName = childElement.textContent
                    "hash" -> hash = HashValue.Builder(childElement).build()
                    else -> fail("Unknown XML element <$tagName>")
                }
            }
            return DependencyConstraint(
                fileName = fileName ?: fail("dependencyConstraint.fileName missing"),
                hash = hash ?: fail("dependencyConstraint.hash missing")
            )
        }
    }
}

class DependencyConstraintsBuilder(private val node: Node): AbstractBuilder<List<DependencyConstraint>> {
    private val constraints = mutableListOf<DependencyConstraint>()

    override fun build(): List<DependencyConstraint> {
        for (childElement in node.childElements) {
            when (val tagName = childElement.tagName) {
                "dependencyConstraint" ->
                    constraints.add(DependencyConstraint.Builder(childElement).build())
                else -> fail("Unknown XML element <$tagName>")
            }
        }
        return unmodifiableList(constraints)
    }
}

private fun loadDocumentFrom(input: InputStream): Document {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
}

fun loadCPKDependencies(input: InputStream): List<CPKDependency> {
    val nodes = loadDocumentFrom(input).getElementsByTagName("cpkDependencies")
    assertEquals(1, nodes.length)
    return CPKDependenciesBuilder(nodes.item(0)).build()
}

fun loadDependencyConstraints(input: InputStream): List<DependencyConstraint> {
    val nodes = loadDocumentFrom(input).getElementsByTagName("dependencyConstraints")
    assertEquals(1, nodes.length)
    return DependencyConstraintsBuilder(nodes.item(0)).build()
}
