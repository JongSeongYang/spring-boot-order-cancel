package com.market.api.service;

import com.market.api.domain.MemberEntity;
import com.market.api.domain.OrderEntity;
import com.market.api.domain.OrderProductEntity;
import com.market.api.domain.ProductEntity;
import com.market.api.dto.Order;
import com.market.api.enums.OrderStatusType;
import com.market.api.enums.PaymentType;
import com.market.api.event.OrderAllCanceledEvent;
import com.market.api.event.OrderCreatedEvent;
import com.market.api.event.OrderPartCanceledEvent;
import com.market.api.exception.CustomResponseStatusException;
import com.market.api.exception.ExceptionCode;
import com.market.api.repository.OrderProductRepository;
import com.market.api.repository.OrderRepository;
import com.market.api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderProductRepository orderProductRepository;
    private final ApplicationEventPublisher publisher;


    @Transactional
    public Order.OrderCommonResponse create(MemberEntity memberEntity, Order.OrderRequestList orderRequestList) {
        try {
            OrderEntity orderEntity = createOrderEntity(memberEntity, orderRequestList);

            orderRequestList.getProductList().forEach(
                    o -> {
                        ProductEntity product = productRepository.findById(o.getId())
                                .orElseThrow(() -> new CustomResponseStatusException(ExceptionCode.PRODUCT_NOT_FOUND, ""));
                        checkPriceAndCount(o, product);
                        OrderProductEntity orderProductEntity = createOrderProductEntity(orderEntity, o, product);
                        orderEntity.setTotalPrice(orderEntity.getTotalPrice() + orderProductEntity.getTotalPrice()); // ?????? ?????? ?????? ??????
                        OrderProductEntity save = orderProductRepository.save(orderProductEntity);
                        product.setStock(product.getStock()-o.getCount()); // ?????? ?????? ??????
                    });
            publisher.publishEvent((new OrderCreatedEvent(orderEntity)));
            orderRepository.save(orderEntity);
        } catch (CustomResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("order >> INTERNAL_ERROR");
            throw new CustomResponseStatusException(ExceptionCode.INTERNAL_ERROR, "");
        }
        return getResponse("ORDER_SUCCESS");
    }

    private void checkPriceAndCount(Order.OrderRequest o, ProductEntity product) {
        if (o.getCount() <= 0) {
            log.error("order >> WRONG_ORDER_REQUEST({})", product.getName());
            throw new CustomResponseStatusException(ExceptionCode.WRONG_ORDER_REQUEST, " >> " + product.getName());
        }
        if (product.getStock() < o.getCount()) {
            log.error("order >> LACK_OF_QUANTITY({})", product.getName());
            throw new CustomResponseStatusException(ExceptionCode.LACK_OF_QUANTITY, " >> " + product.getName());
        }
    }

    public OrderProductEntity createOrderProductEntity(OrderEntity orderEntity, Order.OrderRequest o, ProductEntity product) {
        return  OrderProductEntity.builder()
                .orderEntity(orderEntity)
                .productEntity(product)
                .productPrice(product.getPrice())
                .totalPrice(product.getPrice() * o.getCount())
                .count(o.getCount())
                .status(0)
                .build();
    }

    public OrderEntity createOrderEntity(MemberEntity memberEntity, Order.OrderRequestList orderRequestList) {
        return OrderEntity.builder()
                .memberEntity(memberEntity)
                .orderStatus(OrderStatusType.ORDER_RECEPTION.getStatus())
                .payment(getPayment(orderRequestList.getPayment()))
                .totalPrice(0)
                .build();
    }

    @Transactional
    public Order.OrderCommonResponse cancel(MemberEntity memberEntity, Order.OrderUpdateRequestList requestList) {
        try {
            // ?????? ????????? ?????? ??????
            if (requestList.getProductList().size() == 0) {
                log.error("cancel order >> WRONG_CANCEL_REQUEST");
                throw new CustomResponseStatusException(ExceptionCode.WRONG_CANCEL_REQUEST, "");
            }
            // ????????? ?????? ID??? ??????
            OrderEntity orderEntity = orderRepository.findById(requestList.getOrderId())
                    .orElseThrow(() -> new CustomResponseStatusException(ExceptionCode.ORDER_NOT_FOUND, ""));
            // ???????????? ???????????? ?????? ??????
            if (!memberEntity.getId().equals(orderEntity.getMemberEntity().getId())) {
                log.error("cancel order >> UNAUTHORIZED");
                throw new CustomResponseStatusException(ExceptionCode.UNAUTHORIZED, "");
            }
            // ????????? ???????????? ??????
            if (!orderEntity.getOrderStatus().equals(OrderStatusType.valueOf("ORDER_RECEPTION").getStatus())) {
                log.error("cancel order >> ORDER_CANCELED_FAIL");
                throw new CustomResponseStatusException(ExceptionCode.ORDER_CANCELED_FAIL, "");
            }
            int preSize = (int) orderEntity.getOrderProductEntityList().stream().filter(l -> l.getStatus() == 0).count();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            requestList.getProductList().forEach(
                    l -> {
                        OrderProductEntity orderProductEntity
                                = orderProductRepository.findByOrderEntity_IdAndProductEntity_IdAndCancelTimeIsNull(requestList.getOrderId(), l.getId());
                        orderProductEntity.setStatus(1);
                        orderProductEntity.setCancelTime(now);
                        orderEntity.setTotalPrice(orderEntity.getTotalPrice()-orderProductEntity.getTotalPrice()); // ?????? ?????? ???????????? ??????
                        orderProductEntity.getProductEntity().setStock(orderProductEntity.getProductEntity().getStock()+orderProductEntity.getCount()); // ?????? ?????? ??????
                    });

            int afterSize = (int) orderEntity.getOrderProductEntityList().stream().filter(l -> l.getStatus() == 0).count();
            // ?????? ?????? ????????? ??????
            if (preSize != afterSize && afterSize == 0) {
                orderEntity.setOrderStatus(OrderStatusType.ORDER_CANCEL.getStatus());
                orderEntity.setCancelTime(now);
                publisher.publishEvent(new OrderAllCanceledEvent(orderEntity));
            }
            // ?????? ?????? ????????? ??????
            if (preSize != afterSize && afterSize != 0) {
                publisher.publishEvent(new OrderPartCanceledEvent(orderEntity, now));
            }
        } catch (CustomResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("order >> INTERNAL_ERROR");
            throw new CustomResponseStatusException(ExceptionCode.INTERNAL_ERROR, "");
        }
        return getResponse("CANCEL_SUCCESS");
    }

    private String getPayment(Integer payment) {
        PaymentType paymentType = Arrays.stream(PaymentType.values())
                .filter(p -> p.ordinal() == payment).findFirst()
                .orElseThrow(() -> new CustomResponseStatusException(ExceptionCode.PAYMENT_NOT_FOUND, ""));
        return paymentType.getPayment();
    }

    private Order.OrderCommonResponse getResponse(String message) {
        return Order.OrderCommonResponse.builder()
                .result(true)
                .message(message)
                .build();
    }
}
