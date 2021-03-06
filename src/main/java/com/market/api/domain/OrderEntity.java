package com.market.api.domain;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class OrderEntity extends BaseTimeEntity{

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "memberId")
  private MemberEntity memberEntity;
  private String orderStatus;
  private Integer totalPrice;
  private String payment;
  private LocalDateTime cancelTime; // 취소 시간

  @BatchSize(size = 100)
  @OneToMany(fetch = FetchType.LAZY, mappedBy = "orderEntity")
  private List<OrderProductEntity> orderProductEntityList = new ArrayList<>();

}

