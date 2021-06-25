package me.tatarka.inject.test

import assertk.assertThat
import assertk.assertions.isEqualTo
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.test.module.externalFunction
import kotlin.test.Test

@Component
abstract class FunctionInjectionComponent {
    abstract val bar: bar

    abstract val externalFunction: externalFunction
}

typealias F = String

typealias foo = (F) -> String

@Inject
fun foo(dep: Foo, arg: F): String = arg

typealias bar = () -> String

@Inject
fun bar(foo: foo): String = foo("test")

typealias receiverFun = String.(arg: NamedFoo) -> String

@Inject
fun String.receiverFun(dep: Foo, arg: NamedFoo): String = this

@Component
abstract class ReceiverFunctionInjectionComponent {
    abstract val receiverFun: receiverFun
}

class InjectFunctionTest {

    @Test
    fun generates_a_component_that_provides_a_function() {
        val component = FunctionInjectionComponent::class.create()

        assertThat(component.bar()).isEqualTo("test")
        assertThat(component.externalFunction()).isEqualTo("external")
    }

    @Test
    fun generates_a_component_that_provides_a_function_with_receiver() {
        val component = ReceiverFunctionInjectionComponent::class.create()

        assertThat(with(component) { "test".receiverFun(NamedFoo("arg")) }).isEqualTo("test")
    }
}