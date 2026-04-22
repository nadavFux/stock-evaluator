import React, { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries, LineSeries, createSeriesMarkers } from 'lightweight-charts';
import type { IChartApi } from 'lightweight-charts';
import { Maximize2, Minimize2, Pencil, MousePointer2 } from 'lucide-react';

interface StockChartProps {
    data: any; // StockGraphState
    markers?: any[]; // TradePoint[]
    comparisonData?: any[]; // StockGraphState[]
}

const StockChart: React.FC<StockChartProps> = ({ data, markers, comparisonData = [] }) => {
    const chartContainerRef = useRef<HTMLDivElement>(null);
    const chartRef = useRef<IChartApi | null>(null);
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [tool, setTool] = useState<'pan' | 'line'>('pan');

    useEffect(() => {
        if (!chartContainerRef.current) return;

        const isComparison = comparisonData.length > 0;

        const chart = createChart(chartContainerRef.current, {
            layout: {
                background: { type: ColorType.Solid, color: '#1e293b' },
                textColor: '#94a3b8',
            },
            grid: {
                vertLines: { color: '#334155' },
                horzLines: { color: '#334155' },
            },
            localization: {
                priceFormatter: (price: number) => isComparison ? `${price.toFixed(2)}%` : `$${price.toFixed(2)}`,
            },
            width: chartContainerRef.current.clientWidth,
            height: isFullscreen ? window.innerHeight - 100 : 400,
        });

        const mainSeries = isComparison 
            ? chart.addSeries(LineSeries, { color: '#10b981', lineWidth: 3, title: data.stock.ticker_symbol })
            : chart.addSeries(CandlestickSeries, {
                upColor: '#10b981',
                downColor: '#ef4444',
                borderVisible: false,
                wickUpColor: '#10b981',
                wickDownColor: '#ef4444',
            });

        const maSeries = !isComparison ? chart.addSeries(LineSeries, {
            color: '#3b82f6',
            lineWidth: 2,
            title: 'Long MA',
        }) : null;

        // Prepare data
        if (!data?.closePrices || !data?.dates) {
            console.error('Incomplete data for chart', data);
            return;
        }

        const firstPrice = data.closePrices[0];

        const chartData = data.closePrices.map((price: number, i: number) => {
            if (data.dates[i] === undefined) return null;
            if (isComparison) {
                return {
                    time: data.dates[i],
                    value: ((price - firstPrice) / firstPrice) * 100,
                };
            }
            return {
                time: data.dates[i],
                open: price * 0.99,
                high: price * 1.01,
                low: price * 0.98,
                close: price,
            };
        }).filter(Boolean);

        if (maSeries && data.avgs) {
            const maData = data.avgs.map((avg: number, i: number) => {
                if (data.dates[i] === undefined || avg === null || avg === undefined) return null;
                return {
                    time: data.dates[i],
                    value: avg,
                };
            }).filter(Boolean);
            maSeries.setData(maData as any);
        }

        if (chartData.length === 0) return;
        mainSeries.setData(chartData as any);

        // Comparison Series
        if (isComparison) {
            const colors = ['#3b82f6', '#f59e0b', '#8b5cf6', '#ec4899', '#06b6d4'];
            comparisonData.forEach((comp, idx) => {
                const compSeries = chart.addSeries(LineSeries, {
                    color: colors[idx % colors.length],
                    lineWidth: 2,
                    title: comp.stock.ticker_symbol,
                });
                const compFirstPrice = comp.closePrices[0];
                const compData = comp.closePrices.map((p: number, i: number) => ({
                    time: comp.dates[i],
                    value: ((p - compFirstPrice) / compFirstPrice) * 100,
                })).filter((d: any) => d.time);
                compSeries.setData(compData as any);
            });
        }

        // Add Markers
        if (!isComparison && markers && markers.length > 0) {
            const chartMarkers = markers.map(m => ({
                time: m.date,
                position: m.type === 'BUY' ? 'belowBar' : 'aboveBar',
                color: m.type === 'BUY' ? '#10b981' : '#ef4444',
                shape: m.type === 'BUY' ? 'arrowUp' : 'arrowDown',
                text: m.type
            }));
            createSeriesMarkers(mainSeries as any, chartMarkers as any);
        }

        chart.timeScale().fitContent();
        chartRef.current = chart;

        const handleResize = () => {
            if (chartContainerRef.current && chartRef.current) {
                chartRef.current.applyOptions({ width: chartContainerRef.current.clientWidth });
            }
        };

        window.addEventListener('resize', handleResize);

        return () => {
            window.removeEventListener('resize', handleResize);
            chart.remove();
        };
    }, [data, isFullscreen, comparisonData, tool]);

    return (
        <div className={`relative ${isFullscreen ? 'fixed inset-0 z-[200] bg-[#0f172a] p-4' : 'w-full h-full'}`}>
            <div className="absolute top-4 right-4 z-[210] flex gap-2">
                <div className="flex bg-[#1e293b]/80 backdrop-blur-md rounded-lg border border-slate-700 p-1">
                    <button 
                        onClick={() => setTool('pan')}
                        className={`p-1.5 rounded ${tool === 'pan' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
                        title="Pan Tool"
                    >
                        <MousePointer2 size={18} />
                    </button>
                    <button 
                        onClick={() => setTool('line')}
                        className={`p-1.5 rounded ${tool === 'line' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
                        title="Trendline Tool"
                    >
                        <Pencil size={18} />
                    </button>
                </div>
                <button 
                    onClick={() => setIsFullscreen(!isFullscreen)}
                    className="p-2 bg-[#1e293b]/80 backdrop-blur-md rounded-lg border border-slate-700 text-slate-400 hover:text-white"
                >
                    {isFullscreen ? <Minimize2 size={20} /> : <Maximize2 size={20} />}
                </button>
            </div>
            <div ref={chartContainerRef} className="w-full h-full rounded-xl overflow-hidden border border-slate-700 bg-[#1e293b]" />
        </div>
    );
};

export default StockChart;
