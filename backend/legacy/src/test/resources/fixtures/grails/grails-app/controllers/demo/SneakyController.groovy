package demo

class SneakyController {

    def index() {
        Customer.metaClass.static.findByCustomName = { String name -> null }
    }

    def methodMissing(String name, args) {
        return null
    }
}
