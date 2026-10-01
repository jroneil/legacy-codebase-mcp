package demo

class CustomerController {

    static allowedMethods = [save: "POST", update: "POST", delete: "POST"]

    def customerService

    def index() {
        redirect(action: "list")
    }

    def list() {
        def customers = customerService.findAllCustomers()
        [customers: customers]
    }

    def show(Long id) {
        def customer = customerService.findByLastName(params.lastName)
        render view: "show", model: [customer: customer]
    }
}
