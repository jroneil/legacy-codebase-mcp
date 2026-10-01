beans = {
    customerDao(CustomerDao) {
        sessionFactory = ref('sessionFactory')
    }
    customerService(CustomerService) {
        customerDao = ref('customerDao')
    }
}
